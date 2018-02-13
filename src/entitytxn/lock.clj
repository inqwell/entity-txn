(ns entitytxn.lock
  "A lock manager. Threads may lock values, waiting to obtain
  the lock if one is already outstanding. Locks are re-entrant
  and only released when fully unwound or by force.

  If a lock is currently held by another thread, the current
  thread may wait until the lock becomes available.")

; Maps a locked value to the thread holding that lock
; and the number of times that value has been reentrant locked
(defonce ^:no-doc locks (ref {}))
(defonce ^:no-doc lock-count (ref {}))

; Maps a value to promise(s) thread(s) are waiting for notification.
; The value awaited will be delivered to the promise when the lock is released
(defonce ^:no-doc waits (ref {}))

(defn- waiting!
  "Add the given promise to any already waiting on the
  specified value. Must be called from within a transaction."
  [val prom]
  (if-let [waiters (get @waits val)]
    (alter waits assoc val (conj waiters prom))
    (alter waits assoc val #{prom})))

(defn- get-waiter!
  "Return a single waiter's promise for the given val, or nil if there are no
  waiters. Modifies the waiters by removing the promise, removing the wait entry
  if there are none left. Must be called from within a transaction."
  [val]
  (let [waiters (get @waits val)
        prom    (first waiters)]
    (when prom
      (let [new-waiters (disj waiters prom)]
        (if (empty? new-waiters)
          (alter waits dissoc val)
          (alter waits assoc val new-waiters))))
    prom))

(defn- not-waiting!
  "Remove the given promise from any waiting on the
  specified value. If notify is true and the given promise
  is already realized then check for any further waiters on
  this val return one. Returns nil in all other cases.
  Must be called from within a transaction"
  ([val prom] (not-waiting! val prom false))
  ([val prom notify]
    (when prom
      (let [waiters (-> (get @waits val)
                        (disj prom))]
        (if (empty? waiters)
          (alter waits dissoc val)
          (alter waits assoc val waiters))
        (when (and notify (realized? prom))
          (get-waiter! val))))))

(defn- at-least-zero
  "Subtracts the second arg from the first returning
  any positive result or zero if the result is negative"
  [x y]
  (let [ret (- x y)]
    (if (neg? ret) 0 ret)))

(defn lock!
  "Attempt to lock the given value obtaining the lock if it is available
  or waiting the specified timeout in milliseconds otherwise. A timeout
  of zero means unwilling to wait; negative means wait indefinitely.
  Returns the value as truthy if the lock was obtained, false otherwise."
  ([val] (lock! val -1))
  ([val timeout]
   (when-not val
     (throw (Exception. "Cannot lock falsy")))
   (let [ret-val (atom false)
         wait-promise (atom false)
         rem-timeout (atom timeout)
         cur-time (atom (System/currentTimeMillis))]
     (while (or (not @ret-val)
                @wait-promise)
       (do
         (dosync
           (if-let [holder (get @locks val)]
             (if (= holder (Thread/currentThread))
               (do
                 (alter lock-count assoc val (inc (get @lock-count val))) ; reentering lock we hold
                 (reset! ret-val val))
               (if (or (neg? @rem-timeout)                  ; lock held elsewhere
                       (> @rem-timeout 0))
                 (do
                   (when-not @wait-promise
                     (reset! wait-promise (promise))          ; willing to wait for it
                     (waiting! val @wait-promise)))
                 (do
                   (reset! ret-val :timeout)                ; not (or no longer) willing to wait
                   (not-waiting! val @wait-promise)
                   (reset! wait-promise false))))
             (do                                            ; Lock is available. Claim it.
               (reset! wait-promise false)
               (alter locks assoc val (Thread/currentThread))
               (alter lock-count assoc val 1)
               (reset! ret-val val))))
         (when @wait-promise
           (if (neg? @rem-timeout)
             (reset! ret-val (deref @wait-promise))         ; wait for ever
             (do
               (reset! ret-val (deref @wait-promise @rem-timeout :timeout)) ; wait a finite time (could be zero)
               (if (= @ret-val :timeout)
                 (do
                   (when-let [pass-on-to (dosync
                                           (not-waiting! val @wait-promise true))] ; giving up, might notify someone else
                     (deliver pass-on-to val))
                   (reset! wait-promise false))
                 (do                                        ; offered the lock, loop round to acquire
                   (reset! rem-timeout
                           (at-least-zero @rem-timeout (- (System/currentTimeMillis)
                                                          @cur-time)))
                   (reset! cur-time (System/currentTimeMillis))
                   (reset! ret-val false)
                   (reset! wait-promise false))))))))
     (if (= @ret-val :timeout) false val))))

(defn have-lock?
  "Returns true if the calling thread has the lock of the specfified
  value, false otherwise"
  [val]
  (= (Thread/currentThread) (get @locks val)))

(defn unlock!
  "Unlock the value. If the lock was re-entered decrement the
   count unless forced, in which case unlock. When unlocking notify
   a single waiter, out of any that are waiting.
   Returns the value, throws if this thread is not the lock holder."
  ([val] (unlock! val false))
  ([val force]
   (let [to-notify (atom false)]
     (dosync
       (let [holder (get @locks val)
             locked-count (get @lock-count val)]
         (if (= holder (Thread/currentThread))
           (if (or (= locked-count 1)
                   force)
             (do
               (alter locks dissoc val)
               (alter lock-count dissoc val)
               (reset! to-notify (get-waiter! val)))
             (alter lock-count assoc val (dec locked-count)))
           (throw (ex-info (str "Not the lock holder of " val) @locks)))))
     (when @to-notify
       (deliver @to-notify val))
     val)))
