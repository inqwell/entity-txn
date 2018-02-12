(ns entitytxn.protocols
  "Protocols and multimethods for interfacing transaction
  events to actions.")

(defprotocol TxnEvents
  "Link transaction events to implementations"
  (read-entity [txn-event key-val args]
    "Read none, one or more entity instances from their backing store
    by applying key-val and optional args.")
  (write-entity [txn-event entity-val]
    "Write the given instance to its backing store.")
  (delete-entity [txn-event entity-val]
    "Delete the instance in its backing store.")
  (create-entity [txn-event entity-val]
    "A new, as yet unmanaged domain instance is being created in the
    transaction. Returns the instance to be created when the transaction
    commits.")
  (new-instance [txn-event args]
    "Return a new, unmanaged domain prototype.")
  (mutate-entity [txn-event old-val new-val]
    "An existing instance is being mutated. This event occurs during
    the commit phase and notifies the domain so that it may validate,
    veto by an exception or make further domain changes in the transaction.
    Returns the current in-transaction value.")
  (destroy-entity [txn-event entity-val]
    "An existing, manged instance is being deleted in the
    transaction.")
  (identity-of [txn-event entity-val]
    "Return the identity of the given value. If the argument is
    the identity itself then return that. If the identity cannot
    be determined then throws. An identity must be unique across
    participating instances."))

