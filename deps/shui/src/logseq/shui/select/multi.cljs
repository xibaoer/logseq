(ns logseq.shui.select.multi
  (:require [clojure.string :as string]
            [rum.core :as rum]
            [logseq.shui.popup.core :as popup]
            [logseq.shui.form.core :as form]))

(defn- get-k [item]
  (if (map? item)
    (some->> ((juxt :id :key :label) item)
      (remove nil?)
      (first))
    item))

(defn- get-v
  [item]
  (if (string? item)
    item (or (:title item) (:value item))))

(rum/defc search-input
  [input-props & {:keys [on-enter valid-search-key?]}]
  (let [*el (rum/use-ref nil)
        [down set-down!] (rum/use-state 0)]

    (rum/use-effect!
      (fn []
        (when-let [^js item (and (> down 0)
                              (some-> (rum/deref *el)
                                (.closest ".head")
                                (.-nextSibling)))]
          (some-> (if valid-search-key? (.-nextSibling item) item)
            (.focus))))
      [down])

    [:div.search-input.p-2
     {:ref *el}
     (form/input
       (merge {:placeholder "search"
               :class "!h-8"
               :on-key-up #(case (.-key %)
                             "ArrowDown" (set-down! (inc down))
                             "ArrowUp" nil
                             "Enter" (when (fn? on-enter) (on-enter))
                             :dune)
               :auto-focus true}
         input-props))]))

(defn- simple-search-fn
  [items q]
  (let [q (some-> q (string/trim) (string/lower-case))]
    (if (string/blank? q)
      items
      (filter #(some-> (get-v %)
                 (string/lower-case)
                 (string/starts-with? q)) items))))

(rum/defc x-select
  [items selected-items & {:keys [on-chosen item-render value-render
                                  head-render foot-render open? close!
                                  search-enabled? search-key
                                  search-fn search-key-render
                                  item-props content-props]}]
  (let [x-content popup/dropdown-menu-content
        x-item popup/dropdown-menu-item
        *head-ref (rum/use-ref nil)
        [search-key1 set-search-key!] (rum/use-state search-key)
        search-key1' (some-> search-key1 (string/trim) (string/lower-case))
        valid-search-key? (and search-enabled? (not (string/blank? search-key1')))
        items (if search-enabled?
                (if (fn? search-fn)
                  (search-fn items search-key1)
                  (simple-search-fn items search-key1))
                items)
        close1! #(when (fn? close!) (close!))]

    (rum/use-effect!
      (fn []
        (when (and search-enabled? (false? open?))
          (js/setTimeout #(set-search-key! "") 500)))
      [open?])

    (x-content
      (merge
        {:onCloseAutoFocus false
         :onInteractOutside close1!
         :onEscapeKeyDown close1!
         :class (str (:class content-props)
                  (when valid-search-key? " has-search-key"))}
        (dissoc content-props :class))
      ;; header
      (when (or search-enabled? (fn? head-render))
        [:div.head
         {:ref *head-ref}
         (when search-enabled?
           (search-input
             {:value search-key1
              :on-key-down (fn [^js e]
                             (.stopPropagation e)
                             (case (.-key e)
                               "Escape" (if (string/blank? search-key1)
                                          (some-> (.-target e) (.blur))
                                          (set-search-key! ""))
                               :dune))
              :on-change #(set-search-key! (.-value (.-target %)))}

             {:on-enter (fn []
                          (when-let [head-el (and (not (string/blank? search-key1'))
                                               (rum/deref *head-ref))]
                            (when-let [^js item (.-nextSibling head-el)]
                              (when (contains? #{"menuitemcheckbox" "menuitem"}
                                      (.getAttribute item "role"))
                                (.click item)))))
              :valid-search-key? valid-search-key?}))
         (when head-render (head-render))])
      ;; items
      (for [item items
            :let [selected? (some #(let [k (get-k item)
                                         k' (get-k %)]
                                     (or (= item %)
                                       (and (not (nil? k))
                                         (not (nil? k'))
                                         (= k k'))))
                              selected-items)]]
        (if (fn? item-render)
          (item-render item {:x-item x-item :selected? selected?})
          (let [k (get-k item)
                v (get-v item)]
            (when k
              (let [opts {:selected? selected?}
                    on-click' (:on-click item-props)
                    on-click (fn [e]
                               ;; TODO: return value
                               (when (fn? on-click') (on-click' e))
                               (when (fn? on-chosen)
                                 (on-chosen item opts)))]
                (x-item (merge {:data-k k :on-click on-click} item-props)
                  [:span.flex.items-center.gap-2.w-full
                   (form/checkbox {:checked selected?})
                   (let [v' (if (fn? v) (v item opts) v)]
                     (if (fn? value-render)
                       (value-render v' (assoc opts :item item)) v'))]))))))

      (when (and search-enabled?
              (fn? search-key-render))
        (let [exist-fn (fn []
                         (and (not (string/blank? search-key1))
                           (seq items)
                           (contains? (into #{} (map #(some-> (get-v %) (string/lower-case)) items))
                             (string/lower-case search-key1))))]
          (search-key-render search-key1
            {:items items :x-item x-item :exist-fn exist-fn})))
      ;; footer
      (when (fn? foot-render)
        [:div.foot
         (foot-render)]))))