(ns logseq.outliner.property
  "Property related operations"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.common.util :as common-util]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.db :as ldb]
            [logseq.db.frontend.malli-schema :as db-malli-schema]
            [logseq.db.frontend.order :as db-order]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.build :as db-property-build]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.sqlite.util :as sqlite-util]
            [logseq.graph-parser.block :as gp-block]
            [logseq.outliner.core :as outliner-core]
            [malli.error :as me]
            [malli.util :as mu]))

;; schema -> type, cardinality, object's class
;;           min, max -> string length, number range, cardinality size limit

(defn- build-property-value-tx-data
  ([block property-id value]
   (build-property-value-tx-data block property-id value (= property-id :logseq.task/status)))
  ([block property-id value status?]
   (when (some? value)
     (let [block (assoc (outliner-core/block-with-updated-at {:db/id (:db/id block)})
                        property-id value)
           block-tx-data (cond-> block
                           status?
                           (assoc :block/tags :logseq.class/task))]
       [block-tx-data]))))

(defn- get-property-value-schema
  "Gets a malli schema to validate the property value for the given property type and builds
   it with additional args like datascript db"
  [db property-type property & {:keys [new-closed-value?]
                                :or {new-closed-value? false}}]
  (let [property-val-schema (or (get db-property-type/built-in-validation-schemas property-type)
                                (throw (ex-info (str "No validation for property type " (pr-str property-type)) {})))
        [schema-opts schema-fn] (if (vector? property-val-schema)
                                  (rest property-val-schema)
                                  [{} property-val-schema])]
    [:fn
     schema-opts
     (fn property-value-schema [property-val]
       (db-malli-schema/validate-property-value db schema-fn [property property-val] {:new-closed-value? new-closed-value?}))]))

(defn- fail-parse-double
  [v-str]
  (let [result (parse-double v-str)]
    (or result
        (throw (js/Error. (str "Can't convert \"" v-str "\" to a number"))))))

(defn ^:api convert-property-input-string
  [schema-type v-str]
  (if (and (= :number schema-type) (string? v-str))
    (fail-parse-double v-str)
    v-str))

(defn- update-datascript-schema
  [property {:keys [type cardinality]}]
  (let [ident (:db/ident property)
        cardinality (if (= cardinality :many) :db.cardinality/many :db.cardinality/one)
        new-type (or type (get-in property [:block/schema :type]))]
    (cond->
     {:db/ident ident
      :db/cardinality cardinality}
      (db-property-type/ref-property-types new-type)
      (assoc :db/valueType :db.type/ref))))

(defn ^:api ensure-unique-db-ident
  "Ensures the given db-ident is unique. If a db-ident conflicts, it is made
  unique by adding a suffix with a unique number e.g. :db-ident-1 :db-ident-2"
  [db db-ident]
  (if (d/entity db db-ident)
    (let [existing-idents
          (d/q '[:find [?ident ...]
                 :in $ ?ident-name
                 :where
                 [?b :db/ident ?ident]
                 [(str ?ident) ?str-ident]
                 [(clojure.string/starts-with? ?str-ident ?ident-name)]]
               db
               (str db-ident "-"))
          new-ident (if-let [max-num (->> existing-idents
                                          (keep #(parse-long (string/replace-first (str %) (str db-ident "-") "")))
                                          (apply max))]
                      (keyword (namespace db-ident) (str (name db-ident) "-" (inc max-num)))
                      (keyword (namespace db-ident) (str (name db-ident) "-1")))]
      new-ident)
    db-ident))

(defn upsert-property!
  "Updates property if property-id is given. Otherwise creates a property
   with the given property-id or :property-name option. When a property is created
   it is ensured to have a unique :db/ident"
  [conn property-id schema {:keys [property-name properties]}]
  (let [db @conn
        db-ident (or property-id (db-property/create-user-property-ident-from-name property-name))]
    (assert (qualified-keyword? db-ident))
    (if-let [property (and (qualified-keyword? property-id) (d/entity db db-ident))]
      (let [changed-property-attrs
            ;; Only update property if something has changed as we are updating a timestamp
            (cond-> {}
              (not= schema (:block/schema property))
              (assoc :block/schema schema)
              (and (some? property-name) (not= property-name (:block/original-name property)))
              (assoc :block/original-name property-name))
            property-tx-data
            (cond-> []
              (seq changed-property-attrs)
              (conj (outliner-core/block-with-updated-at
                     (merge {:db/ident db-ident}
                            (common-util/dissoc-in changed-property-attrs [:block/schema :cardinality]))))
              (or (not= (:type schema) (get-in property [:block/schema :type]))
                  (and (:cardinality schema) (not= (:cardinality schema) (keyword (name (:db/cardinality property)))))
                  (and (= :default (:type schema)) (not= :db.type/ref (:db/valueType property)))
                  (seq (:property/closed-values property)))
              (conj (update-datascript-schema property schema)))
            tx-data (concat property-tx-data
                            (when (seq properties)
                              (mapcat
                               (fn [[property-id v]]
                                 (build-property-value-tx-data property property-id v)) properties)))
            many->one? (and (db-property/many? property) (= :one (:cardinality schema)))]
        (when (seq tx-data)
          (d/transact! conn tx-data {:outliner-op :update-property
                                     :property-id (:db/id property)
                                     :many->one? many->one?})))
      (let [k-name (or (and property-name (name property-name))
                       (name property-id))
            db-ident' (ensure-unique-db-ident @conn db-ident)]
        (assert (some? k-name)
                (prn "property-id: " property-id ", property-name: " property-name))
        (d/transact! conn
                     [(sqlite-util/build-new-property db-ident' schema {:original-name k-name})]
                     {:outliner-op :new-property})))))

(defn- validate-property-value
  [schema value]
  (me/humanize (mu/explain-data schema value)))

(defn- page-name->map
  "Wrapper around logseq.graph-parser.block/page-name->map that adds in db"
  [db original-page-name with-id?]
  (gp-block/page-name->map original-page-name with-id? db true nil))

(defn- resolve-tag!
  "Change `v` to a tag's db id if v is a string tag, e.g. `#book`"
  [conn v]
  (when (and (string? v)
             (common-util/tag? (string/trim v)))
    (let [tag-without-hash (common-util/safe-subs (string/trim v) 1)
          tag (or (page-ref/get-page-name tag-without-hash) tag-without-hash)]
      (when-not (string/blank? tag)
        (let [db @conn
              e (ldb/get-case-page db tag)
              e' (if e
                   (do
                     (when-not (contains? (:block/type e) "tag")
                       (d/transact! conn [{:db/id (:db/id e)
                                           :block/type (set (conj (:block/type e) "class"))}]))
                     e)
                   (let [m (assoc (page-name->map @conn tag true)
                                  :block/type #{"class"})]
                     (d/transact! conn [m])
                     m))]
          (:db/id e'))))))

(defn- ->eid
  [id]
  (if (uuid? id) [:block/uuid id] id))

(defn- raw-set-block-property!
  "Adds the raw property pair (value not modified) to the given block if the property value is valid"
  [conn block property property-type new-value]
  (let [k-name (:block/original-name property)
        property-id (:db/ident property)
        schema (get-property-value-schema @conn property-type property)]
    (if-let [msg (and
                  (not= new-value :logseq.property/empty-placeholder)
                  (validate-property-value schema
                                           ;; normalize :many values for components that only provide single value
                                           (if (and (db-property/many? property) (not (coll? new-value)))
                                             #{new-value}
                                             new-value)))]
      (let [msg' (str "\"" k-name "\"" " " (if (coll? msg) (first msg) msg))]
        (throw (ex-info "Schema validation failed"
                        {:type :notification
                         :payload {:message msg'
                                   :type :warning}})))
      (let [status? (= :logseq.task/status (:db/ident property))
            tx-data (build-property-value-tx-data block property-id new-value status?)]
        (d/transact! conn tx-data {:outliner-op :save-block})))))

(defn create-property-text-block!
  "Creates a property value block for the given property and value. Adds it to
  block if given block. Takes options:
   * `template-id`: which template the new block belongs to"
  [conn block-id property-id value {:keys [template-id new-block-id]}]
  (let [property (d/entity @conn property-id)
        block (when block-id (d/entity @conn block-id))]
    (when property
      (let [new-value-block (cond-> (db-property-build/build-property-value-block (or block property) property value)
                              new-block-id
                              (assoc :block/uuid new-block-id)
                              (int? template-id)
                              (assoc :block/tags template-id
                                     :logseq.property/created-from-template template-id))]
        (d/transact! conn [new-value-block] {:outliner-op :insert-blocks})
        (let [property-id (:db/ident property)]
          (when (and property-id block)
            (when-let [block-id (:db/id (d/entity @conn [:block/uuid (:block/uuid new-value-block)]))]
              (raw-set-block-property! conn block property (get-in property [:block/schema :type]) block-id)))
          (:block/uuid new-value-block))))))

(defn- get-property-value-eid
  [db property-id raw-value]
  (first
   (d/q '[:find [?v ...]
          :in $ ?property-id ?raw-value
          :where
          [?b ?property-id ?v]
          [?v :block/content ?raw-value]]
        db
        property-id
        raw-value)))

(defn- find-or-create-property-value
  "Find or create a property value. Only to be used with properties that have ref types"
  [conn property-id v]
  (or (get-property-value-eid @conn property-id (str v))
      (let [v-uuid (create-property-text-block! conn nil property-id (str v) {})]
        (:db/id (d/entity @conn [:block/uuid v-uuid])))))

(defn set-block-property!
  "Updates a block property's value for an existing property-id and block.
  Property value is sanitized and if property is a ref type, automatically
  handles a raw property value i.e. you can pass \"value\" instead of the
  property value entity. Also handle db attributes as properties"
  [conn block-eid property-id v]
  (let [block-eid (->eid block-eid)
        db @conn
        _ (assert (qualified-keyword? property-id) "property-id should be a keyword")
        block (d/entity @conn block-eid)
        property (d/entity @conn property-id)
        _ (assert (some? property) (str "Property " property-id " doesn't exist yet"))
        property-type (get-in property [:block/schema :type] :default)
        v' (or (resolve-tag! conn v) v)
        db-attribute? (contains? db-property/db-attribute-properties property-id)
        ref-type? (db-property-type/ref-property-types property-type)]
    (if db-attribute?
      (d/transact! conn [{:db/id (:db/id block) property-id v'}]
                   {:outliner-op :save-block})
      (let [new-value* (cond
                         (= v' :logseq.property/empty-placeholder)
                         (if (= property-type :checkbox) false v')

                         ref-type?
                         (if (and (integer? v')
                                  (or (and (= property-type :number) (= property-id (:db/ident (:logseq.property/created-from-property (d/entity db v')))))
                                      (not= property-type :number)))
                           v'
                           (find-or-create-property-value conn property-id v'))
                         :else
                         v')
            ;; don't modify maps
            new-value (if (or (sequential? new-value*) (set? new-value*))
                        (if (= :coll property-type)
                          (vec (remove string/blank? new-value*))
                          (set (remove string/blank? new-value*)))
                        new-value*)
            existing-value (get block property-id)]
        (when-not (= existing-value new-value)
          (raw-set-block-property! conn block property property-type new-value))))))

(defn batch-set-property!
  "Sets properties for multiple blocks. Automatically handles property value refs.
   Does no validation of property values.
   NOTE: This fn only works for properties with cardinality equal to `one`."
  [conn block-ids property-id v]
  (assert property-id "property-id is nil")
  (let [block-eids (map ->eid block-ids)
        property (d/entity @conn property-id)
        _ (assert (some? property) (str "Property " property-id " doesn't exist yet"))
        _ (assert (not (db-property/many? property)) "Property must be cardinality :one in batch-set-property!")
        property-type (get-in property [:block/schema :type] :default)
        _ (assert v "Can't set a nil property value must be not nil")
        v' (if (db-property-type/value-ref-property-types property-type)
             (find-or-create-property-value conn property-id v)
             v)
        status? (= :logseq.task/status (:db/ident property))
        txs (mapcat
             (fn [eid]
               (if-let [block (d/entity @conn eid)]
                 (build-property-value-tx-data block property-id v' status?)
                 (js/console.error "Skipping setting a block's property because the block id could not be found:" eid)))
             block-eids)]
    (when (seq txs)
      (d/transact! conn txs {:outliner-op :save-block}))))

(defn batch-remove-property!
  [conn block-ids property-id]
  (let [block-eids (map ->eid block-ids)]
    (when-let [property (d/entity @conn property-id)]
      (let [txs (mapcat
                 (fn [eid]
                   (when-let [block (d/entity @conn eid)]
                     (let [value (get block property-id)
                           block-value? (= :default (get-in property [:block/schema :type] :default))
                           property-block (when block-value? (d/entity @conn (:db/id value)))
                           retract-blocks-tx (when (and property-block
                                                        (some? (get property-block :logseq.property/created-from-property)))
                                               (let [txs-state (atom [])]
                                                 (outliner-core/delete-block conn txs-state property-block {:children? true})
                                                 @txs-state))]
                       (concat
                        [[:db/retract eid (:db/ident property)]]
                        retract-blocks-tx))))
                 block-eids)]
        (when (seq txs)
          (d/transact! conn txs {:outliner-op :save-block}))))))

(defn remove-block-property!
  [conn eid property-id]
  (let [eid (->eid eid)]
    (if (contains? db-property/db-attribute-properties property-id)
      (when-let [block (d/entity @conn eid)]
        (d/transact! conn
                     [[:db/retract (:db/id block) property-id]]
                     {:outliner-op :save-block}))
      (batch-remove-property! conn [eid] property-id))))

(defn delete-property-value!
  "Delete value if a property has multiple values"
  [conn block-eid property-id property-value]
  (when-let [property (d/entity @conn property-id)]
    (let [block (d/entity @conn block-eid)]
      (when (and block (not= property-id (:db/ident block)) (db-property/many? property))
        (d/transact! conn
                     [[:db/retract (:db/id block) property-id property-value]]
                     {:outliner-op :save-block})))))

(defn collapse-expand-block-property!
  "Notice this works only if the value itself if a block (property type should be either :default or :template)"
  [conn block-id property-id collapse?]
  (let [f (if collapse? :db/add :db/retract)]
    (d/transact! conn
                 [[f block-id :block/collapsed-properties property-id]]
                 {:outliner-op :save-block})))

(defn ^:api get-class-parents
  [tags]
  (let [tags' (filter (fn [tag] (contains? (:block/type tag) "class")) tags)
        *classes (atom #{})]
    (doseq [tag tags']
      (when-let [parent (:class/parent tag)]
        (loop [current-parent parent]
          (when (and
                 current-parent
                 (contains? (:block/type parent) "class")
                 (not (contains? @*classes (:db/id parent))))
            (swap! *classes conj current-parent)
            (recur (:class/parent current-parent))))))
    @*classes))

(defn ^:api get-block-classes-properties
  [db eid]
  (let [block (d/entity db eid)
        classes (->> (:block/tags block)
                     (sort-by :block/name)
                     (filter (fn [tag] (contains? (:block/type tag) "class"))))
        class-parents (get-class-parents classes)
        all-classes (->> (concat classes class-parents)
                         (filter (fn [class]
                                   (seq (:class/schema.properties class)))))
        all-properties (-> (mapcat (fn [class]
                                     (map :db/ident (:class/schema.properties class))) all-classes)
                           distinct)]
    {:classes classes
     :all-classes all-classes           ; block own classes + parent classes
     :classes-properties all-properties}))

(defn- closed-value-other-position?
  [db property-id block-properties]
  (and
   (some? (get block-properties property-id))
   (let [schema (:block/schema (d/entity db property-id))]
     (= (:position schema) "block-beginning"))))

(defn get-block-other-position-properties
  [db eid]
  (let [block (d/entity db eid)
        own-properties (keys (:block/properties block))]
    (->> (:classes-properties (get-block-classes-properties db eid))
         (concat own-properties)
         (filter (fn [id] (closed-value-other-position? db id (:block/properties block))))
         (distinct))))

(defn block-has-viewable-properties?
  [block-entity]
  (let [properties (:block/properties block-entity)]
    (or
     (seq (:block/alias block-entity))
     (and (seq properties)
          (not= properties [:logseq.property/icon])))))

(defn- build-closed-value-tx
  [db property property-type resolved-value {:keys [id icon description]
                                             :or {description ""}}]
  (let [block (when id (d/entity db [:block/uuid id]))
        block-id (or id (ldb/new-block-id))
        icon (when-not (and (string? icon) (string/blank? icon)) icon)
        description (string/trim description)
        description (when-not (string/blank? description) description)
        resolved-value (if (= property-type :number) (str resolved-value) resolved-value)
        tx-data (if block
                  [(let [schema (:block/schema block)]
                     (cond->
                      (outliner-core/block-with-updated-at
                       {:block/uuid id
                        :block/content resolved-value
                        :block/closed-value-property (:db/id property)
                        :block/schema (if description
                                        (assoc schema :description description)
                                        (dissoc schema :description))})
                       icon
                       (assoc :logseq.property/icon icon)))]
                  (let [max-order (:block/order (last (:property/closed-values property)))
                        new-block (-> (db-property-build/build-closed-value-block block-id resolved-value
                                                                                  property {:icon icon
                                                                                            :description description})
                                      (assoc :block/order (db-order/gen-key max-order nil)))]
                    [new-block
                     (outliner-core/block-with-updated-at
                      {:db/id (:db/id property)})]))]
    tx-data))

(defn upsert-closed-value!
  "id should be a block UUID or nil"
  [conn property-id {:keys [id value] :as opts}]
  (assert (or (nil? id) (uuid? id)))
  (let [db @conn
        property (d/entity db property-id)
        property-schema (:block/schema property)
        property-type (get property-schema :type :default)]
    (when (contains? db-property-type/closed-value-property-types property-type)
      (let [value' (if (string? value) (string/trim value) value)
            resolved-value (try
                             (convert-property-input-string (:type property-schema) value')
                             (catch :default e
                               (js/console.error e)
                               (throw (ex-info "Property converted failed"
                                               {:type :notification
                                                :payload {:message (str e)
                                                          :type :error}}))
                               nil))
            validate-message (validate-property-value
                              (get-property-value-schema @conn property-type property {:new-closed-value? true})
                              resolved-value)]
        (cond
          (some (fn [b]
                  (and (= (str resolved-value) (str (or (db-property/property-value-when-closed b)
                                                        (:block/uuid b))))
                       (not= id (:block/uuid b))))
                (:property/closed-values property))

          ;; Make sure to update frontend.handler.db-based.property-test when updating ex-info message
          (throw (ex-info "Closed value choice already exists"
                          {:error :value-exists
                           :type :notification
                           :payload {:message "Choice already exists"
                                     :type :warning}}))

          validate-message

          ;; Make sure to update frontend.handler.db-based.property-test when updating ex-info message
          (throw (ex-info "Invalid property value"
                          {:error :value-invalid
                           :type :notification
                           :payload {:message validate-message
                                     :type :warning}}))

          (nil? resolved-value)
          nil

          :else
          (d/transact! conn
                       (build-closed-value-tx @conn property property-type resolved-value opts)
                       {:outliner-op :save-block}))))))

(defn add-existing-values-to-closed-values!
  "Adds existing values as closed values and returns their new block uuids"
  [conn property-id values]
  (when-let [property (d/entity @conn property-id)]
    (when (seq values)
      (let [values' (remove string/blank? values)]
        (assert (every? uuid? values') "existing values should all be UUIDs")
        (let [values (keep #(d/entity @conn [:block/uuid %]) values')]
          (when (seq values)
            (let [value-property-tx (map (fn [id]
                                           {:db/id id
                                            :block/type "closed value"
                                            :block/closed-value-property (:db/id property)})
                                         (map :db/id values))
                  property-tx (outliner-core/block-with-updated-at {:db/id (:db/id property)})]
              (d/transact! conn (cons property-tx value-property-tx)
                           {:outliner-op :save-blocks}))))))))

(defn delete-closed-value!
  "Returns true when deleted or if not deleted displays warning and returns false"
  [conn property-id value-block-id]
  (when-let [value-block (d/entity @conn value-block-id)]
    (cond
      (ldb/built-in? value-block)
      (do
      ;; (notification/show! "The choice can't be deleted because it's built-in." :warning)
        (prn "The choice can't be deleted because it's built-in.")
        false)

      :else
      (let [tx-data [[:db/retractEntity (:db/id value-block)]
                     (outliner-core/block-with-updated-at
                      {:db/id property-id})]]
        (d/transact! conn tx-data)))))

(defn get-property-block-created-block
  "Get the root block and property that created this property block."
  [db eid]
  (let [block (d/entity db eid)
        created-from-property (:logseq.property/created-from-property block)]
    {:from-property-id (:db/id created-from-property)}))

(defn class-add-property!
  [conn class-id property-id]
  (when-let [class (d/entity @conn class-id)]
    (if (contains? (:block/type class) "class")
      (d/transact! conn
                   [[:db/add (:db/id class) :class/schema.properties property-id]]
                   {:outliner-op :save-block})
      (throw (ex-info "Can't add a property to a block that isn't a class"
                      {:class-id class-id :property-id property-id})))))

(defn class-remove-property!
  [conn class-id property-id]
  (when-let [class (d/entity @conn class-id)]
    (when (contains? (:block/type class) "class")
      (when-let [property (d/entity @conn property-id)]
        (when-not (ldb/built-in-class-property? class property)
          (d/transact! conn [[:db/retract (:db/id class) :class/schema.properties property-id]]
                       {:outliner-op :save-block}))))))