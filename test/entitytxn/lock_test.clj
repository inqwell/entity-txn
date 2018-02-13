(ns entitytxn.lock-test
  (:require [clojure.test :refer :all]
            [entitytxn.lock :refer :all]))

(deftest lock-simple
  (let [lock-val "hello"]
    (testing "Lock something, other thread will not obtain lock"
      (is (= lock-val (lock! lock-val)))
      (is (false? @(future (lock! lock-val 0))))   ; no timeout
      (is (false? @(future (lock! lock-val 500)))) ; 0.5 sec timeout
      (is (= lock-val (unlock! lock-val)))
      (is (empty? @locks))
      (is (empty? @waits)))))

(deftest lock-notify
  (let [lock-val "hello"]
    (testing "Lock something, other thread waits for lock"
      (is (= lock-val (lock! lock-val)))      ; lock it
      (let [f (future (lock! lock-val 2000)   ; wait for long enough
                      (unlock! lock-val))]    ; tidy up!
        (Thread/sleep 1000)                  ; wait less time then unlock
        (is (= lock-val (unlock! lock-val)))
        (is (= lock-val @f))
        (is (empty? @locks))
        (is (empty? @waits))))))             ; future had the lock

(deftest lock-notify-one-of-two
  (let [lock-val 2
        p1 (promise)
        p2 (promise)]
    (testing "Lock something, two other threads wait for unlock, one gets lock, other doesn't"
      (is (= lock-val (lock! lock-val)))                     ; lock it
      (let [f (fn [p]
                (deliver p true)                            ; sync with main thread
                (if (lock! lock-val 2000)                    ; wait for long enough
                  (do
                    (Thread/sleep 4000)                     ; force other thread to timeout
                    (unlock! lock-val))                      ; got the lock, release it
                  (- lock-val)))                            ; didn't get the lock, return negative of lock-val
            fut1 (future (f p1))
            fut2 (future (f p2))]
        (deref p1)                                          ; wait less time then unlock
        (deref p2)                                          ; wait less time then unlock
        (is (= lock-val (unlock! lock-val)))
        (is (zero? (+ @fut1 @fut2)))
        (is (empty? @locks))
        (is (empty? @waits))))))

(defn- stress-lock-manager
  [lock-range lock-wait-time]
  (let [locks-todo 5000
        achieved (atom 0)
        timed-out (atom 0)
        futures (vec (for [number (take locks-todo (cycle (shuffle (range 1 lock-range))))]
                       (future (if (lock! number 10)
                                 (do
                                   (unlock! number)
                                   (swap! achieved inc))
                                 (swap! timed-out inc)))))]
    (doseq [f futures] @f)                                  ; wait for all futures to finish
    (println "Locks achieved " @achieved)
    (println "Locks timed out " @timed-out)
    (is (= locks-todo (+ @achieved @timed-out)))
    (is (empty? @locks))
    (is (empty? @waits))))

(deftest stress
  (testing "Thrash the lock manager")
  (stress-lock-manager 10 10)
  (stress-lock-manager 10 50)
  (stress-lock-manager 100 50))

