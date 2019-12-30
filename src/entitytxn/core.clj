(ns entitytxn.core
  "Transaction state management"
  (:refer-clojure :exclude [assoc merge])
  (:require [entitytxn.lock :as l]
            [linked.core :as linked]
            [entitytxn.protocols :as p]))

(def ^:dynamic ^:no-doc *txn* {:root true
                               :events (p/->NilTxnEvents)
                               :assoc clojure.core/assoc
                               :merge clojure.core/merge})

(defn ^{:doc/format :markdown} set-transaction-defaults
  "Set up defaults to be used in transactions and nested transactions.
  This function will typically be called from an application's state
  management setup

   + `:events connect transaction state to type and io system (defaults to none)
   + `:assoc` how to assoc maps (defaults to clojure.core/assoc)
   + `:merge` how to merge maps (defaults to clojure.core/merge)
   + `:on-commit` function accepting two arguments `[participants actions]` to call when the
   transaction commits.
   + `:on-abort` a function of zero arguments called when transaction aborts (exception or explicit)
   + `:on-start` a function of zero arguments called before transaction body is executed
   + `:on-end` function of zero arguments called after transaction commits or aborts"
  [& defaults]
  (let [{:keys [events
                on-commit
                on-abort
                assoc
                merge]
         :or {events (p/->NilTxnEvents)
              assoc clojure.core/assoc
              merge clojure.core/merge}} defaults]
    (alter-var-root #'*txn* (constantly {:events events
                                         :on-commit on-commit
                                         :on-abort on-abort
                                         :assoc assoc
                                         :merge merge
                                         :root true}))))

(defn ^:no-doc setup-and-validate
  "Initialise a new transaction environment.
  args is a map allowing defaults to be overridden in this
  transaction."
  [args]
  (let [txn (clojure.core/merge *txn*
                                args
                                {:participants (atom (linked/map))
                                 :commit-participants (atom (linked/map))
                                 :actions      (atom {})
                                 :locks        (atom (linked/set))
                                 :state        (atom :new)
                                 :parent       (if-not (:root *txn*) *txn*)
                                 :root         false})]
    (if-not (:events txn)
      (throw (Exception. ":events must be specified or inherited")))
    txn))

(defn- get-events
  []
  (:events *txn*))

(defn- transaction-running?
  "Throws if a transaction is not currently running. Returns true otherwise."
  ([] (transaction-running? nil))
  ([states]
   (if (:root *txn*)
     (throw (Exception. "Not in any transaction scope"))
     (if (or (nil? states) (@(:state *txn*) states))
       true
       (throw (Exception. (str "Not in expected transaction state of " states)))))))

(defn lock!
  "Attempt to lock the given value obtaining the lock if it is available
  or waiting the specified timeout in milliseconds otherwise. A timeout of
  zero means unwilling to wait; negative means wait indefinitely.

  Locks taken out become part of the current transaction's state. Locks held
  in the current transaction are released automatically when the transaction
  closes, and in reverse order of locking.

  Returns the value as truthy if the lock was obtained, throws otherwise."
  ([val] (lock! val -1))
  ([val timeout]
   (transaction-running?)
   (let [locked (l/lock! val timeout)]
     (if locked
       (do
         (swap! (:locks *txn*) conj val)
         val)
       (throw (Exception. (str "Could not obtain lock of " val)))))))

(defn ^:no-doc unlock-all!
  "Release all locks held by the current transaction. Locks are
  released in the reverse order in which they were initially taken
  out."
  []
  (doseq [l (-> *txn*
                :locks
                deref
                reverse)]
    (l/unlock! l true)))

(defn- in-transaction?
  []
  (and (:actions *txn*) true))

(defn managed?
  "Return whether an instance is managed and therefore able
  to participate in a transaction for mutate/delete. An
  instance that is unmanaged can be marked for creation in the
  transaction."
  [instance]
  (-> instance
      meta
      :managed))

(defn- get-identity
  "Return the instance's identity."
  [instance]
  (-> (get-events)
      (p/identity-of instance)))

(defn- get-parent-transaction
  "Returns the parent transaction of the transaction argument,
  or nil if given the top-level transaction."
  ([] (get-parent-transaction *txn*))
  ([txn]
   (:parent txn)))

(defn- get-txn-state
  []
  (transaction-running?)
  @(:state *txn*))

(defn- committing?
  []
  (= (get-txn-state) :committing))

(defn- get-txn-instance
  "Get the in-transaction value for the given instance or id
  from the current or specified transaction.
  Returns the transaction value (which, for mutate operations
  is a map of :old-val and :new-val values) or nil if the
  instance is not participating in the transaction."
  ([instance] (get-txn-instance instance *txn*))
  ([instance txn]
  (let [id (get-identity instance)
        participants @(:participants txn)
        alt-participants @(:commit-participants txn)]
    (or (get alt-participants id)
        (get participants id)))))

(defn- add-active-instance
  "Add an instance to the current transaction. If the "
  [id instance action]
  (let [actions (:actions *txn*)
        participants (:participants *txn*)
        alt-participants (:commit-participants *txn*)
        use-participants (cond
                           (contains? @participants id)
                           participants

                           (committing?)
                           alt-participants

                           :else
                           participants)]
    (swap! use-participants
           clojure.core/assoc id instance)
    (swap! actions clojure.core/assoc id action)))

  ;(let [participants (if (committing?)
  ;                     (:commit-participants *txn*)
  ;                     (:participants *txn*))
  ;      actions      (:actions *txn*)]
  ;  (swap! participants
  ;         clojure.core/assoc id instance)
  ;  (swap! actions clojure.core/assoc id action)))

(defn- remove-participant
  "Remove a participant from this transaction. Note - the only
  transitions this is required for is create -> delete
  and delete -> create -> delete."
  [id]
  (let [participants (:participants *txn*)
        alt-participants (:commit-participants *txn*)
        actions      (:actions *txn*)]
    (swap! participants clojure.core/dissoc id)
    (swap! alt-participants clojure.core/dissoc id)
    (swap! actions clojure.core/dissoc id)))

(defn- get-action
  "Get the action for the given transaction and identity"
  [txn id]
  (when-let [actions (-> txn
                         :actions)]
    (get @actions id)))

(defn- participating-as
  "Determine whether the given instance (or instance identity)
  is participating in the current or specified transaction. Depending
  on the action given, the return value is
      :create/delete - the value in the transaction
      :mutate - the current value in the transaction
      :joined - whichever of the above applies
      false when participating but not with the specified action
      nil when not present in the transaction at all"
  ([instance action] (participating-as instance action *txn*))
  ([instance action txn]
   (let [id (get-identity instance)
         l-action (get-action txn id)]
     (if (or (= l-action action)
             (and l-action (= action :joined)))
       (condp = l-action
         :delete (get-txn-instance id txn)
         :create (or (get-txn-instance id txn)
                     (get-txn-instance (clojure.core/assoc id :recreate true) txn))
         :mutate (:new-val (get-txn-instance id txn)))
       (if l-action
         false
         nil)))))

(defn- search-for-action
  "Look to see if the instance (or instance identity) is participating
  in this (or the given) transaction or any parent. Returns
    :create/delete - the value
    :mutate - the current value
    false - found but not with the specified action or not found at all
  The instance argument may instead be the instance's identity."
  ([instance action] (search-for-action instance action *txn*))
  ([instance action txn]
   (transaction-running?)
   (let [id (get-identity instance)]
     (loop [txn     txn
            parent  (get-parent-transaction txn)
            txn-val (participating-as instance action txn)]
       (cond
         (false? txn-val) false  ; in this txn but not with specified action. Stop looking
         (nil? txn-val) (if-not parent   ; no parent left to check
                          false
                          (recur parent  ; not in this txn, try parent
                                 (get-parent-transaction parent)
                                 (participating-as instance action parent)))
         :else txn-val)))))

(defn- joined-inner?
  [instance txn]
  (search-for-action instance :joined txn))

(defn joined?
  "Return true if the given instance (or identity) is joined in some way in
  the current or any parent transaction."
  [instance]
  (joined-inner? instance *txn*))

(defn- ensure-no-id-change
  "Throw if the given instance has a different id from the original. Returns
  the transaction id otherwise"
  [instance]
  (let [id (get-identity instance)
        new-id (select-keys instance (keys id))
        old-id (select-keys id (keys new-id))]   ; cleanse any keys added for txn uniqueness
    (if (not= new-id old-id)
      (throw (ex-info "Attempt to mutate instance identity" instance))
      id)))

(defn- set-in-txn-mutate
  "If this is a managed value and not yet in the transaction
  remember its original and present state. Otherwise just update
  the present state. If the value is not being managed, does nothing.
  Returns the present state."
  [old-val new-val]
  (if (managed? old-val)
    (do
      (transaction-running? #{:started :committing})
      (let [id (get-identity new-val)]
        (if (participating-as id :delete)
          (throw (ex-info "Attempt to mutate deleted instance" old-val)))
        (if (joined-inner? id (get-parent-transaction))
          (throw (ex-info "Instance is joined in a parent transaction" old-val)))
        (let [{t-old-val :old-val} (get-txn-instance id)]
          (add-active-instance id {:new-val new-val
                                   :old-val (or t-old-val old-val)}
                               :mutate)
          new-val)))
    new-val))

(defn- set-txn-state
  [state]
  (reset! (:state *txn*) state))

(defn ^:no-doc transaction-ended?
  []
  (transaction-running?)
  ((get-txn-state) #{:aborted :committed}))

(defn- get-commit-instances
  "Fetch the list of items to commit. In the simple case, and when committing the
  transaction on the original change set, the :participants list is returned.
  While committing this set it is possible, via calls to TxnEvents/mutate-entity,
  that more instances are entered into the transaction. These are accumulated
  in :commit-participants and will be committed after the original set.
  This process can continue until there are no entries in :commit-participants.
  This function has the following side-effects:
    1. When returning the initial set the transaction state is set to :committing
    2. When returning subsequent sets (from :commit-participants) that set is merged
       to :participants so that this 'master' list is a record of everything that
       has been in this transaction."
  []
  (let [participants (:participants *txn*)
        alt-participants (:commit-participants *txn*)
        cur-alt @alt-participants]
    (if (= (get-txn-state) :committing)
      (do
        (swap! participants clojure.core/merge cur-alt)
        (reset! alt-participants (empty cur-alt))
        cur-alt)
      (do
        (set-txn-state :committing)
        @participants))))

(defn- get-participants
  "Returns the current participants."
  []
  (clojure.core/merge @(:participants *txn*)
         @(:commit-participants *txn*)))

(defn- clear-txn
  "Removes all participants and actions from the current transaction"
  []
  (let [participants (:participants *txn*)
        commit-participants (:commit-participants *txn*)
        actions (:actions *txn*)]
    (reset! participants (linked/map))
    (reset! commit-participants (linked/map))))

(defn- write-participant*
  [action events val]
  (condp = action
    :create (p/write-entity events val)
    :mutate (p/write-entity events (:new-val val))
    :delete (p/delete-entity events val)
    (throw (Exception. "Unexpected action"))))

(defn write-txn-state
  "Write the values contained in the current transaction to backing store
  via TxnEvents.write-entity for create and mutate and TxnEvents.delete-entity
  for delete. This is a convenience function that clients
  can call via their :on-commit action, for example, within a transaction
  binding for the particular backing store."
  [participants]
  (transaction-running? #{:committed})
  (let [actions      @(:actions *txn*)
        events       (get-events)]
    (doseq [[id val] participants]
      (write-participant* (get actions id)
                          events
                          val))))

(defn ^:no-doc do-on-start
  []
  (set-txn-state :started)
  (if-let [on-start (:on-start *txn*)]
    (on-start)))

(defn ^:no-doc do-on-end
  []
  (if-let [on-end (:on-end *txn*)]
    (on-end)))

(defn commit
  "Commit the current transaction. Each instance joined for mutate will have
  TxnEvents.mutate-entity called, passing the original and current values. If any instances
  are entered into the transaction by the actions of the mutate calls,
  these will in turn be committed on subsequent passes. When all participants have been
  committed any :on-commit function will be called, passing all participants. This function
  cannot further affect transaction state."
  []
  (let [commit-fn (:on-commit *txn*)
        events (get-events)
        actions      @(:actions *txn*)]
    (loop [participants (get-commit-instances)]
      (when (not-empty participants)
        (do
          ; call mutate via the events
          (doseq [[id instance] participants]
            (condp = (get actions id)
              :mutate (do
                        (let [{:keys [old-val new-val]} instance
                              mutated (p/mutate-entity events old-val new-val)]
                          (ensure-no-id-change mutated)
                          (set-in-txn-mutate new-val
                                             mutated)))
              (do) ; nothing for create/delete
              ))
          ; Pass the current participants to any :on-commit function
          (recur (get-commit-instances)))))
    (set-txn-state :committed)
    (when commit-fn
      (commit-fn (get-participants) actions))))

(defn abort
  "Abort the transaction. Transaction state is discarded and any :on-abort function
  is called. Further use of the transaction is not permitted.

  abort is called if the transaction body incurs an exception."
  []
  (transaction-running? #{:started :committing})
  (clear-txn)
  (set-txn-state :aborted)
  (if-let [abort-fn (:on-abort *txn*)]
    (abort-fn)))

(defmacro in-transaction
  "Opens a new transaction with optional arguments and runs the body in it, committing
  when the transaction closes. To specify args, pass a map which will be merged with
  any established by set-transaction-defaults, for example to supply a specific :on-commit
  function. Code runs in an implicit do, with any :on-start function called first.

  If an exception occurs, abort is called and the exception rethrown, otherwise
  commit is called. Any :on-end function is always run. Returns nil."
  [args & body]
  `(let [args# ~(if (map? args) args {})]
     (binding [*txn* (setup-and-validate args#)]
       (try
         (do-on-start)
         ~(if (map? args) `(do ~@body) `(do ~args ~@body))
         (catch Throwable ~'t
           (abort)
           (throw ~'t))
         (finally
           (try
             (when-not (transaction-ended?)
               (commit))
             (finally
               (unlock-all!)
               (do-on-end)))))
       nil)))

(defn- mark-managed
  "If the given instance is not already marked for mutate and therefore
  already in the transaction, mark the given instance as managed and therefore
  eligible to participate in a transaction for mutate/delete. If already being
  mutated in this or a parent transaction return the current value."
  [instance]
  (or (search-for-action instance :mutate)
      (vary-meta instance clojure.core/assoc :managed true)))

(defn in-deletion?
  "Return the instance, as truthy true if the given instance (or identity) is
  marked for deletion in the current or any parent transaction. Falsy otherwise."
  [instance]
  (search-for-action instance :delete *txn*))

(defn in-creation?
  "Return the instance if the given instance (or identity) is marked for
  creation in the current or any parent transaction. Falsy otherwise."
  [instance]
  (search-for-action instance :create *txn*))

(defn read-instance
  "Read one or more instances via TxnEvents.read-entity. Within
  a transaction, for any values returned, the following occurs:
    1) If the value is being deleted, do not return it (or if a
       non-unique key, remove it from the sequence)
    2) If the value is being mutated, return the current value
    3) Return the value as-is, marked as managed.
  Values in creation are not returned as there is no connection
  with the underlying persistence logic, however such values
  can be queried for participation with in-creation?.

  Any args are passed to the underlying implementation, so must
  be compatible with that.

  If no transaction is running the result(s) from the TxnEvents.read-entity
  is returned unmanaged. Instance(s) cannot take part in a transaction when
  acquired outside it."
  [key-val & args]
  (let [result (-> (get-events)
                   (p/read-entity key-val args))]
    (if (in-transaction?)
      (let [filter-deleted (fn [instance] (not (in-deletion? instance)))]
        (cond
          (nil? result) result
          (map? result) (if (filter-deleted result)
                          (mark-managed result) ; single result
                          nil)
          :else (map mark-managed (filter filter-deleted result))))
      result)))

(defn assoc
  "As for clojure.core/assoc, however if the target map is managed
  track its mutation in the transaction. If the map is not
  managed the transaction state is unaffected.

  The :assoc function in the transaction settings is used to perform
  the map operation. Consider using typeops/assoc to maintain value types
  and a fixed key set."
  ([map key val]
   (->> ((:assoc *txn*) map key val)
        (set-in-txn-mutate map)))
  ([map key val & kvs]
    (->> (apply (:assoc *txn*) map key val kvs)
         (set-in-txn-mutate map))))

(defn merge
  "As for clojure.core/merge, however if the target map is managed
  track its state in the transaction. If the map is notmanaged the
  transaction state is unaffected.

  The :merge function in the transaction settings is used to perform
  the map operation. Consider using typeops/assoc to maintain value types
  and a fixed key set."
  [& maps]
  (when (some identity maps)
    (let [old (first maps)
          ret (apply (:merge *txn*) maps)]
      (set-in-txn-mutate old ret))))

(defn make-new-instance
  "Makes a new, as yet unmanaged, instance of a domain type according to the
  given arguments, by calling TxnEvents.new-instance. The args are appropriate to
  the underlying system of domain types. Does not call TxnEvents.create-entity
  until create is called."
  [& args]
  (-> (get-events)
      (p/new-instance args)))

(defn create
  "Initialise the instance via TxnEvents.create-entity and join the result into the
  transaction for creation. If the instance is already managed or its identity is already
  joined in this or a parent transaction, this is an error.
  Returns the value being created in the transaction."
  [instance]
  (transaction-running? #{:started :committing})
  (when (managed? instance)
    (throw (ex-info "Cannot create instances already managed" instance)))
  (let [to-create (-> (get-events)
                      (p/create-entity instance))
        id (get-identity to-create)]
    (when (participating-as id :create)
      (throw (ex-info "Already creating given value" to-create)))
    (when (joined-inner? id (get-parent-transaction))
      (throw (ex-info "Already joined in parent transaction" to-create)))
    (when (read-instance id)
      (throw (ex-info "Already exists in domain" to-create)))
    (let [create-id (if (participating-as id :delete)
                      (clojure.core/assoc id :recreate true)
                      id)]
      (cond
        (and (:recreate create-id)
             (joined? create-id))
        (throw (ex-info "Already recreating given value" to-create))

        (and (not (:recreate create-id))
             (read-instance create-id))
        (throw (ex-info "Creating something that already exists" to-create))

        :else
        (add-active-instance create-id to-create :create))
      to-create)))

(defn delete
  "Join the given value into the transaction for deletion. If the
  value is not managed or already scheduled for deletion this is an error.
  If the value has been previously created it is removed from the transaction.
  When successful calls TxnEvents.destroy-entity and returns true. Throws
  otherwise."
  [val]
  (transaction-running? #{:started :committing})
  (let [id (get-identity val)
        recreate-id (clojure.core/assoc id :recreate true)
        parent-txn (get-parent-transaction)]
    (cond
      (or (joined-inner? id parent-txn)
          (joined-inner? recreate-id parent-txn))
      (throw (ex-info "Joined in a parent transaction" val))

      (participating-as id :create)    ; normal :create, not a {:recreate true}
      (remove-participant id)

      (participating-as recreate-id :create)    ; remove recreate
      (remove-participant recreate-id)

      (and (managed? val)
           (participating-as id :delete))
      (throw (ex-info "Cannot delete twice" val))
      :else ; NB it's OK if the value was being mutated to later then delete it.
      (add-active-instance id val :delete))
    (-> (get-events)
        (p/destroy-entity val))
    true))
