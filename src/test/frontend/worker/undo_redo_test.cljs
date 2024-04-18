(ns frontend.worker.undo-redo-test
  (:require [cljs.pprint :as pp]
            [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.test.generators :as t.gen]
            [frontend.test.helper :as test-helper]
            [frontend.worker.fixtures :as worker-fixtures]
            [frontend.worker.undo-redo :as undo-redo]
            [logseq.db :as ldb]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.tree :as otree]
            [frontend.worker.state :as worker-state]
            ["fs" :as fs-node]
            [logseq.db.sqlite.util :as sqlite-util]
            [datascript.transit :as dt]
            [logseq.outliner.transaction :as outliner-tx]
            [logseq.outliner.core :as outliner-core]))

(def ^:private page-uuid (random-uuid))
(def ^:private init-data (test-helper/initial-test-page-and-blocks {:page-uuid page-uuid}))

(defn- start-and-destroy-db
  [f]
  (test-helper/db-based-start-and-destroy-db
   f
   {:init-data (fn [conn] (d/transact! conn init-data {:gen-undo-ops? false}))}))

(use-fixtures :each
  start-and-destroy-db
  worker-fixtures/listen-test-db-to-gen-undo-ops-fixture)


(def ^:private gen-non-exist-block-uuid gen/uuid)

(defn- gen-block-uuid
  [db & {:keys [non-exist-frequency] :or {non-exist-frequency 1}}]
  (gen/frequency [[9 (t.gen/gen-available-block-uuid db {:page-uuid page-uuid})]
                  [non-exist-frequency gen-non-exist-block-uuid]]))

(defn- gen-parent-left-pair
  [db]
  (gen/frequency [[9 (t.gen/gen-available-parent-left-pair db {:page-uuid page-uuid})]
                  [1 (gen/vector gen-non-exist-block-uuid 2)]]))

(defn- gen-move-block-op
  [db]
  (gen/let [block-uuid (gen-block-uuid db)
            [parent left] (gen-parent-left-pair db)]
    [:frontend.worker.undo-redo/move-block
     {:block-uuid block-uuid
      :block-origin-left left
      :block-origin-parent parent}]))

(defn- gen-insert-block-op
  [db]
  (gen/let [block-uuid (gen-block-uuid db)]
    [:frontend.worker.undo-redo/insert-blocks
     {:block-uuids [block-uuid]}]))

(defn- gen-remove-block-op
  [db]
  (gen/let [block-uuid (gen-block-uuid db {:non-exist-frequency 90})
            [parent left] (gen-parent-left-pair db)
            content gen/string-alphanumeric]
    [:frontend.worker.undo-redo/remove-block
     {:block-uuid block-uuid
      :block-entity-map
      {:block/uuid block-uuid
       :block/left left
       :block/parent parent
       :block/content content}}]))

(defn- gen-update-block-op
  [db]
  (let [gen-content-attr (gen/let [content gen/string-alphanumeric]
                           [:block-origin-content content])
        gen-collapsed-attr (gen/let [v gen/boolean]
                             [:block-origin-collapsed v])
        gen-tags-attr (gen/let [tags (gen/vector (gen-block-uuid db))]
                        [:block-origin-tags tags])]
    (gen/let [block-uuid (gen-block-uuid db)
              attrs (gen/vector (gen/one-of [gen-content-attr gen-collapsed-attr gen-tags-attr]) 3)]
      [:frontend.worker.undo-redo/update-block
       (into {:block-uuid block-uuid} attrs)])))

(def ^:private gen-boundary (gen/return [:frontend.worker.undo-redo/boundary]))

(defn- gen-op
  [db & {:keys [insert-block-op move-block-op remove-block-op update-block-op boundary-op]
         :or {insert-block-op 2
              move-block-op 2
              remove-block-op 4
              update-block-op 2
              boundary-op 2}}]
  (gen/frequency [[insert-block-op (gen-insert-block-op db)]
                  [move-block-op (gen-move-block-op db)]
                  [remove-block-op (gen-remove-block-op db)]
                  [update-block-op (gen-update-block-op db)]
                  [boundary-op gen-boundary]]))

(defn- get-db-block-set
  [db]
  (set
   (apply concat
          (d/q '[:find ?uuid
                 :where
                 [?b :block/uuid ?uuid]
                 [?b :block/parent ?parent]
                 [?b :block/left ?left]
                 [?parent :block/uuid ?parent-uuid]
                 [?left :block/uuid ?left-uuid]]
               db))))


(defn- check-block-count
  [{:keys [op tx]} current-db]
  (case (first op)
    :frontend.worker.undo-redo/move-block
    (assert (= (:block-origin-left (second op))
               (:block/uuid (:block/left (d/entity current-db [:block/uuid (:block-uuid (second op))]))))
            {:op op :tx-data (:tx-data tx) :x (keys tx)})

    :frontend.worker.undo-redo/update-block
    (assert (some? (d/entity current-db [:block/uuid (:block-uuid (second op))]))
            {:op op :tx-data (:tx-data tx)})

    :frontend.worker.undo-redo/insert-blocks
    (let [entities (map #(d/entity current-db [:block/uuid %]) (:block-uuids (second op)))]
      (assert (every? nil? entities)
              {:op op :tx-data (:tx-data tx) :x (keys tx)}))

    :frontend.worker.undo-redo/remove-block
    (assert (some? (d/entity current-db [:block/uuid (:block-uuid (second op))]))
            {:op op :tx-data (:tx-data tx) :x (keys tx)})
    ;; else
    nil))

(defn- undo-all
  [conn page-uuid]
  (binding [undo-redo/*undo-redo-info-for-test* (atom nil)]
    (loop [i 0]
      (let [r (undo-redo/undo test-helper/test-db-name-db-version page-uuid conn)
            current-db @conn]
        (check-block-count @undo-redo/*undo-redo-info-for-test* current-db)
        (if (not= :frontend.worker.undo-redo/empty-undo-stack r)
          (recur (inc i))
          (prn :undo-count i))))))

(defn- redo-all
  [conn page-uuid]
  (binding [undo-redo/*undo-redo-info-for-test* (atom nil)]
    (loop [i 0]
      (let [r (undo-redo/redo test-helper/test-db-name-db-version page-uuid conn)
            current-db @conn]
        (check-block-count @undo-redo/*undo-redo-info-for-test* current-db)
        (if (not= :frontend.worker.undo-redo/empty-redo-stack r)
          (recur (inc i))
          (prn :redo-count i))))))

(defn- undo-all-then-redo-all
  [conn]
  (undo-all conn page-uuid)
  (redo-all conn page-uuid))

(deftest undo-redo-gen-test
  (let [conn (db/get-db false)
        all-remove-ops (gen/generate (gen/vector (gen-op @conn {:remove-block-op 1000}) 1000))]
    (#'undo-redo/push-undo-ops test-helper/test-db-name-db-version page-uuid all-remove-ops)
    (prn :block-count-before-init (count (get-db-block-set @conn)))
    (loop [i 0]
      (when (not= :frontend.worker.undo-redo/empty-undo-stack
                  (undo-redo/undo test-helper/test-db-name-db-version page-uuid conn))
        (recur (inc i))))
    (prn :block-count (count (get-db-block-set @conn)))
    (undo-redo/clear-undo-redo-stack)
    (testing "move blocks"
      (let [origin-graph-block-set (get-db-block-set @conn)
            ops (gen/generate (gen/vector (gen-op @conn {:move-block-op 1000 :boundary-op 500}) 1000))]
        (prn :generate-move-ops (count ops))
        (#'undo-redo/push-undo-ops test-helper/test-db-name-db-version page-uuid ops)

        (undo-all-then-redo-all conn)
        (undo-all-then-redo-all conn)
        (undo-all-then-redo-all conn)

        (is (= origin-graph-block-set (get-db-block-set @conn)))))

    (testing "random ops"
      (let [origin-graph-block-set (get-db-block-set @conn)
            ops (gen/generate (gen/vector (gen-op @conn) 1000))]
        (prn :generate-random-ops (count ops))
        (#'undo-redo/push-undo-ops test-helper/test-db-name-db-version page-uuid ops)

        (undo-all-then-redo-all conn)
        (undo-all-then-redo-all conn)
        (undo-all-then-redo-all conn)

        (is (= origin-graph-block-set (get-db-block-set @conn)))))))

(defn- print-page-stat
  [db page-uuid page-name]
  (let [blocks (ldb/get-page-blocks db page-name {})]
    (pp/pprint
     {:block-count (count blocks)
      :undo-op-count (count (get-in @(:undo/repo->page-block-uuid->undo-ops @worker-state/*state)
                                    [test-helper/test-db-name-db-version page-uuid]))
      :redo-op-count (count (get-in @(:undo/repo->page-block-uuid->redo-ops @worker-state/*state)
                                    [test-helper/test-db-name-db-version page-uuid]))})))

(defn- print-page-blocks-tree
  [db page-uuid page-name]
  (let [blocks (ldb/get-page-blocks db page-name {})]
    (prn ::page-block-tree)
    (pp/pprint
     (walk/postwalk
      (fn [x]
        (if (map? x)
          (cond-> (select-keys x [:db/id])
            (seq (:block/children x))
            (assoc :block/children (:block/children x)))
          x))
      (otree/blocks->vec-tree test-helper/test-db-name-db-version db
                              blocks page-uuid)))))

(deftest ^:wip undo-redo-outliner-op-gen-test
  (try
    (let [conn (db/get-db false)]
      (loop [num 100]
        (when (> num 0)
          (if-let [op (gen/generate (t.gen/gen-insert-blocks-op @conn {:page-uuid page-uuid}))]
            (do (outliner-op/apply-ops! test-helper/test-db-name-db-version conn
                                        [op] "MMM do, yyyy" nil)
                (recur (dec num)))
            (recur (dec num)))))
      (println "================ random inserts ================")
      (print-page-stat @conn page-uuid "test")
      (undo-all conn page-uuid)
      (print-page-stat @conn page-uuid "test")
      (redo-all conn page-uuid)
      (print-page-stat @conn page-uuid "test")

      (loop [num 1000]
        (when (> num 0)
          (if-let [op (gen/generate (t.gen/gen-move-blocks-op @conn {:page-uuid page-uuid}))]
            (do (outliner-op/apply-ops! test-helper/test-db-name-db-version conn
                                        [op] "MMM do, yyyy" nil)
                (recur (dec num)))
            (recur (dec num)))))
      (println "================ random moves ================")
      (print-page-stat @conn page-uuid "test")
      (undo-all conn page-uuid)
      (print-page-stat @conn page-uuid "test")
      (redo-all conn page-uuid)
      (print-page-stat @conn page-uuid "test")

      (loop [num 100]
        (when (> num 0)
          (if-let [op (gen/generate (t.gen/gen-delete-blocks-op @conn {:page-uuid page-uuid}))]
            (do (outliner-op/apply-ops! test-helper/test-db-name-db-version conn
                                        [op] "MMM do, yyyy" nil)
                (recur (dec num)))
            (recur (dec num)))))
      (println "================ random deletes ================")
      (print-page-stat @conn page-uuid "test")
      (undo-all conn page-uuid)
      (print-page-stat @conn page-uuid "test")
      (try (redo-all conn page-uuid)
           (catch :default e
             (print-page-blocks-tree @conn page-uuid "test")
             (throw e)))
      (print-page-stat @conn page-uuid "test"))
    (catch :default e
      (let [data (ex-data e)]
        (fs-node/writeFileSync "debug.json" (sqlite-util/write-transit-str data))
        (throw (ex-info "stop now" {}))))))



(deftest ^:wip2 debug-test
  (let [{:keys [origin-db illegal-entity blocks-to-delete blocks-to-move target-block sibling?]}
        (dt/read-transit-str (str (fs-node/readFileSync "debug.json")))
        conn (d/conn-from-db origin-db)
        _ (prn :blocks-to-move blocks-to-move :blocks-to-delete blocks-to-delete)
        block-entities (map #(d/entity @conn %) blocks-to-delete)
        blocks-to-move (map #(d/entity @conn %) blocks-to-move)]
    (prn "before transact" (d/pull origin-db '[*] illegal-entity))
    (when (seq blocks-to-delete)
      (prn :delete-blocks block-entities)
      (outliner-tx/transact!
       {:gen-undo-ops? false
        :outliner-op :delete-blocks
        :transact-opts {:repo test-helper/test-db-name-db-version
                        :conn conn}}
       (outliner-core/delete-blocks! test-helper/test-db-name-db-version conn
                                     "MMM do, yyyy"
                                     block-entities
                                     {})))
    (when (seq blocks-to-move)
      (prn :move-blocks blocks-to-move :target target-block)
      (outliner-tx/transact!
       {:gen-undo-ops? false
        :outliner-op :delete-blocks
        :transact-opts {:repo test-helper/test-db-name-db-version
                        :conn conn}}
       (outliner-core/move-blocks! test-helper/test-db-name-db-version conn
                                   blocks-to-move
                                   (d/entity @conn target-block)
                                   sibling?)))

    (prn "after transact" (d/pull @conn '[*] illegal-entity))))
