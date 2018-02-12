(ns entitytxn.entity
  "Connect transactions to entity-core with EntityTxnEvents
  implementation of TxnEvents"
  (:require [entitytxn.protocols :refer [TxnEvents identity-of]]
            [entity.core :as e]))

(deftype EntityTxnEvents []
  TxnEvents
  (read-entity [_ key-val args]
    ; args is expected to carry
    ;   :entity the defentity we wish to read
    ;   :key the key to apply
    (let [key-m (meta key-val)
          {:keys [entity key]
           :or {entity (:entity key-m)
                key    (or (:key key-m)
                           :primary)}} args]
      (e/read-entity key-val entity key)))
  (write-entity [_ entity-val]
    (e/write-instance entity-val))
  (delete-entity [txn-event entity-val]
    (e/delete-instance entity-val))
  (create-entity [this entity-val]
    ; Get the entity-core configured create function for
    ; this type and call it, returning the value as the
    ; new in-transaction instance. The create function must
    ; exist and we check it has established the identity
    (when-not (-> entity-val
                  meta
                  :entity)
      (throw (ex-info "Not an entity instance" entity-val)))
    (if-let [create-fn (e/get-create-fn entity-val)]
      (let [ret (create-fn entity-val)]
        (when-not (identity-of this ret)
          (throw (ex-info "Identity was not set" (meta ret))))
        ret)
      (throw (ex-info "No create function" (meta entity-val)))))
  (new-instance [_ args]
    ; args is expected to carry
    ;   :entity the defentity we wish to return a new instance
    ;   :init any initial value (optional)
    (let [{:keys [entity init]} args]
      (e/new-instance entity init)))
  (mutate-entity [_ old-val new-val]
    (if-let [mutate-fn (e/get-mutate-fn old-val)]
      (mutate-fn old-val new-val)))
  (destroy-entity [_ entity-val]
    (if-let [destroy-fn (e/get-destroy-fn entity-val)]
      (destroy-fn entity-val)))
  (identity-of [_ entity-val]
    (let [id (e/get-primary-key entity-val)]
      (when id
        (assoc id :entity (-> entity-val
                              meta
                              :entity))))))

(defn entity-txn-events
  "Helper function to return a EntityTxnEvents."
  []
  (EntityTxnEvents.))

