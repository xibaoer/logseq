(ns frontend.handler.editor.impl.db
  "DB-based graph implementation"
  (:require [frontend.db :as db]
            [clojure.string :as string]
            [frontend.format.block :as block]))

(defn- remove-non-existed-refs!
  [refs]
  (remove (fn [x] (or
                   (and (vector? x)
                        (= :block/uuid (first x))
                        (nil? (db/entity x)))
                   (nil? x))) refs))

(defn wrap-parse-block
  [{:block/keys [content left level] :as block}]
  (let [block (or (and (:db/id block) (db/pull (:db/id block))) block)
        block (if (string/blank? content)
                block
                (let [block (merge block
                                   (block/parse-block (assoc block :block/content content)))]
                  (update block :block/refs remove-non-existed-refs!)))
        block (if (and left (not= (:block/left block) left)) (assoc block :block/left left) block)]
    (-> block
        (dissoc
         :block.temp/top?
         :block.temp/bottom?
         :block/pre-block?
         :block/unordered?)
        (assoc :block/content content)
        (merge (if level {:block/level level} {})))))