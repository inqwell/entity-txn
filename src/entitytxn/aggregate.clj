(ns entitytxn.aggregate
  "Interface entity.aggregate into transactions"
  (:require [entity.protocol :refer [read-fn]]
            [entitytxn.core :refer [read-instance]]
            [entity.protocol :refer [read-fn]]
            [entity.aggregate :as agg]))

(defmethod read-fn :txn-read
  [_ key-val & args]
  (apply read-instance key-val args))

(defn aggregate
  "Supplies (read-entity ...) as the reading function for entity.aggregate/aggregate
  so that structures contain managed instances."
  [data & opts]
  (apply agg/aggregate data :read-f :txn-read opts))


