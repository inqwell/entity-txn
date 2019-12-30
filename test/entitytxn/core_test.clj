(ns entitytxn.core-test
  (:refer-clojure :exclude [assoc merge + - * /])
  (:require [clojure.test :refer :all]
            [entitytxn.core :refer :all]
            [entitytxn.aggregate :as a]
            [entitytxn.entity :refer [entity-txn-events]]
            [entity.core :refer :all]
            [entity.sql.hug :as sql]
            [clojure.java.jdbc :as jdbc]
            [typeops.assign :as t]
            [typeops.core :refer :all]
            [clojure.java.io :as io])
  (:import (java.text SimpleDateFormat)))

(def now (java.sql.Timestamp. 0))

(def fmt (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss"))

(defn str-to-sql-date
  [s]
  (java.sql.Timestamp. (.getTime (.parse fmt "2017-06-02T12:03:42"))))


(def fruit-db-spec {:jdbc-url "jdbc:h2:./test.db"})
(def ^:dynamic *fruit-db* (sql/connect! fruit-db-spec))

(def fruit-db-opts {:entity-opts
                    {:server-type :h2}})

; Scalar types
(defscalar :foo/StringId "")
(defscalar :foo/NumDays 0)
(defscalar :foo/LongVal 0)
(defscalar :foo/LongName "")
(defscalar :foo/Money 0.00M)
(defscalar :foo/Date now)
(defscalar :foo/DateTime now)
(defscalar :foo/AddressLine "")

; Enum types
(defenum :foo/Freezable {:y "Y"
                         :n "N"} :y)

(defenum :foo/Active {:y 1
                      :n 0} :y)

(defentity :foo/Fruit
           [Fruit          :foo/StringId
            Description    :foo/LongName
            ShelfLife      :foo/NumDays = 1
            Active         (enum-val :foo/Active :y)
            Freezable      (enum-val :foo/Freezable :n)]
           [Fruit]
           :keys {:all         {:unique? false
                                :cached? false
                                :fields  []}
                  :by-active   {:unique? false
                                :cached? true
                                :fields  [Active]}
                  :by-supplier {:unique? false
                                :cached? false
                                :fields  [:foo/Supplier.Supplier]}
                  :filter      {:unique? false
                                :cached? false
                                :fields  [Fruit
                                          Active :as FruitActive
                                          Freezable
                                          ShelfLife :as MinShelfLife = 7
                                          ShelfLife :as MaxShelfLife]}}
           :io (sql/bind-connection *fruit-db* "sql/fruit.sql" fruit-db-opts)
           :create (fn [instance] (primary-key-to-meta instance))
           :mutate (fn [old new]
                     ; For some strange reason the energy in a fruit is proportional
                     ; to its shelf life. Who knew?
                     (when (not= (:ShelfLife new) (:ShelfLife old))
                       (if-let [nutrition (read-instance new :entity :foo/Nutrition)]
                         (assoc nutrition :KCalPer100g (* (:ShelfLife new) 2))))
                     new)
           :join (fn [instance] (comment stuff))
           :destroy (fn [instance]
                      ; When destroying a Fruit, destroy orphaned FruitSupplier
                      ; instances too. We'll choose to leave Nutrition
                      (doseq [fruit-supplier (read-instance instance
                                                            :key :by-fruit
                                                            :entity :foo/FruitSupplier)]
                        (delete fruit-supplier)))
           :alias :Fruit)

(defentity :foo/Nutrition
           [Fruit       :foo/StringId
            KCalPer100g :foo/LongVal
            Fat         :foo/LongVal
            Salt        :foo/LongVal]
           [Fruit]
           :io (sql/bind-connection *fruit-db* "sql/nutrition.sql" fruit-db-opts)
           :create (fn [instance] (primary-key-to-meta instance))
           :mutate (fn [old new]
                     new)
           :alias :Nutrition)

(defentity :foo/FruitSupplier
           [Fruit       :foo/StringId
            Supplier    :foo/StringId
            PricePerKg  :foo/Money
            LastOrdered now]
           [Fruit Supplier]
           :keys {:by-fruit {:unique? false
                             :cached true
                             :fields [Fruit]}
                  :filter {:unique? false
                           :cached? false
                           :fields  [Fruit
                                     Supplier
                                     :foo/Fruit.Active :as FruitActive
                                     :foo/Supplier.Active :as :SupplierActive
                                     LastOrdered :as FromDate
                                     LastOrdered :as ToDate
                                     :foo/Fruit.Freezable
                                     :foo/Fruit.ShelfLife :as MinShelfLife
                                     :foo/Fruit.ShelfLife :as MaxShelfLife]}
                  :all          {:unique? false
                                 :cached? false
                                 :fields  []}}
           :io (sql/bind-connection *fruit-db* "sql/fruitsupplier.sql" fruit-db-opts))

(defentity :foo/Supplier
           [Supplier    :foo/StringId
            Active      (enum-val :foo/Active :y)
            Address1    :foo/AddressLine
            Address2    :foo/AddressLine]
           [Supplier]
           :keys {:by-fruit {:unique? false
                             :cached? false
                             :fields [:foo/Fruit.Fruit]}
                  :all {:unique? false
                        :cached? false
                        :fields  []}}
           :io (sql/bind-connection *fruit-db* "sql/supplier.sql" fruit-db-opts))

; In setting up the transaction environment we are saying
; 1. Uses entity-core as the type system
; 2. Use typeops for "mutating" maps (preserves types of values)
; 3. When committing, write everything in the txn to the DB, in a DB transaction
(defn init-txn
  []
  (set-transaction-defaults
    :events (entity-txn-events)   ; use entity-core as domain type system
    :assoc t/assoc                ; use typeops when altering maps
    :merge t/merge
    :on-commit (fn [participants actions] ; write the participating instances to the DB using a DB transaction
                 (sql/with-transaction [*fruit-db*]
                                       (write-txn-state participants)))))

; Initialise the transaction library. This would be part of state management
(init-txn)

(def strawberry (make-new-instance
                  :entity :foo/Fruit
                  :init {:Fruit       "Strawberry"
                         :Description "Soft Summer Fruit"
                         :ShelfLife   14
                         :Active      (enum-val :foo/Active :y)
                         :Freezable   (enum-val :foo/Freezable :y)}))

(def banana (make-new-instance
              :entity :foo/Fruit
              :init {:Fruit       "Banana"
                     :Description "Yellow and not straight"
                     :ShelfLife   21
                     :Active      (enum-val :foo/Active :y)
                     :Freezable   (enum-val :foo/Freezable :n)}))

(def pineapple (make-new-instance
                 :entity :foo/Fruit
                 :init {:Fruit       "Pineapple"
                  :Description "Edible Bromeliad"
                  :ShelfLife   46
                  :Active      (enum-val :foo/Active :n)  ; Out of season
                  :Freezable   (enum-val :foo/Freezable :n)}))

(def strawberry-nutrition (make-new-instance
                            :entity :foo/Nutrition
                            :init {:Fruit       "Strawberry"
                                   :KCalPer100g 28
                                   :Fat         0
                                   :Salt        0}))

(def banana-nutrition (make-new-instance
                        :entity :foo/Nutrition
                        :init {:Fruit       "Banana"
                               :KCalPer100g 50
                               :Fat         1
                               :Salt        0}))

(def pineapple-nutrition (make-new-instance
                           :entity :foo/Nutrition
                           :init {:Fruit       "Pineapple"
                                  :KCalPer100g 45
                                  :Fat         0
                                  :Salt        1}))

; bad-apple is not an instance created with make-new-instance
; As such it's cannot take part in a transaction.
(def bad-apple {:Fruit       "BadApple"
                :Description "Always one"
                :ShelfLife   0
                :Active      (enum-val :foo/Active :y)
                :Freezable   (enum-val :foo/Freezable :n)})

(def strawberries-from-kent (make-new-instance
                              :entity :foo/FruitSupplier
                              :init {:Fruit       "Strawberry"
                                     :Supplier    "Kent Fruits"
                                     :PricePerKg  2.75M
                                     :LastOrdered (str-to-sql-date "2017-06-02T12:03:42")}))

(def strawberries-from-sussex (make-new-instance
                                :entity :foo/FruitSupplier
                                :init {:Fruit       "Strawberry"
                                       :Supplier    "Sussex Fruits"
                                       :PricePerKg  2.79M
                                       :LastOrdered (str-to-sql-date "2017-06-02T13:07:31")}))

(def pineapples-from-sussex (make-new-instance
                              :entity :foo/FruitSupplier
                              :init {:Fruit       "Pineapple"
                                     :Supplier    "Sussex Fruits"
                                     :PricePerKg  3.49M
                                     :LastOrdered (str-to-sql-date "2017-06-01T23:07:31")}))

(def fruit-supplier-mappings [strawberries-from-kent
                              strawberries-from-sussex
                              pineapples-from-sussex])

(def strawberry-supplier-mappings [strawberries-from-kent
                                   strawberries-from-sussex])

(def kent-fruits (make-new-instance
                   :entity :foo/Supplier
                   :init {:Supplier "Kent Fruits"
                          :Active   (enum-val :foo/Active :y)
                          :Address1 "The Fruit Farm"
                          :Address2 "Deepest Kent"}))

(def sussex-fruits (make-new-instance
                     :entity :foo/Supplier
                     :init {:Supplier "Sussex Fruits"
                            :Active   (enum-val :foo/Active :y)
                            :Address1 "All Fruits"
                            :Address2 "South Downs"}))

(defn disconnect
  []
  (sql/disconnect! *fruit-db*))

(defn delete-test-db []
  (io/delete-file "test.db.mv.db" true)
  (io/delete-file "test.db.trace.db" true))

(defn create-fruits-table []
  (jdbc/db-do-commands
    *fruit-db*
    false
    ["DROP TABLE Fruit IF EXISTS;"
     (jdbc/create-table-ddl
       :Fruit
       [[:Fruit "VARCHAR(32)" "PRIMARY KEY"]
        [:Description "VARCHAR(32)"]
        [:ShelfLife :int]
        [:Active :int]
        [:Freezable "CHAR(1)"]])]))

(defn create-nutrition-table []
  (jdbc/db-do-commands
    *fruit-db*
    false
    ["DROP TABLE Nutrition IF EXISTS;"
     (jdbc/create-table-ddl
       :Nutrition
       [[:Fruit "VARCHAR(32)" "PRIMARY KEY"]
        [:KCalPer100g :int]
        [:Fat :int]
        [:Salt :int]])]))

(defn create-fruits-supplier-table []
  (jdbc/db-do-commands
    *fruit-db*
    false
    ["DROP TABLE FruitSupplier IF EXISTS;"
     (jdbc/create-table-ddl
       :FruitSupplier
       [[:Fruit "VARCHAR(32) NOT NULL"]
        [:Supplier "VARCHAR(32) NOT NULL"]
        [:PricePerKg "DECIMAL(20,2)"]
        [:LastOrdered "DATETIME"]])
     "ALTER TABLE FruitSupplier ADD PRIMARY KEY (Fruit, Supplier);"]))

(defn create-supplier-table []
  (jdbc/db-do-commands
    *fruit-db*
    false
    ["DROP TABLE Supplier IF EXISTS;"
     (jdbc/create-table-ddl
       :Supplier
       [[:Supplier "VARCHAR(32) PRIMARY KEY"]
        [:Active :int]
        [:Address1 "VARCHAR(32)"]
        [:Address2 "VARCHAR(32)"]])]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; Single thing, using primary key for reads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-simple
  (create-fruits-table)
  (testing "Simple create and in-transaction presence test"
    (in-transaction
      (let [created (create strawberry)]
        (is (in-creation? created))))
    ; Once committed read back direct from the DB where we should find it
    (is (= strawberry (read-instance strawberry)))))

(deftest created-already-in-domain
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Create something that already exists in the domain. Throws."
    (in-transaction
      (is (thrown? Exception (create strawberry))))))

(deftest mutate-and-related
  (create-fruits-table)
  (create-nutrition-table)
  (write-instance strawberry)
  (write-instance strawberry-nutrition)
  (testing "Mutate something, then see mutated value and related inside txn and in DB after commit"
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)
              in-txn-strawberry (merge managed-strawberry long-life-strawberry)]
          (is (joined? managed-strawberry))
          (is (= long-life-strawberry (read-instance strawberry)))))
      (is (= long-life-strawberry
             (read-instance strawberry)))
      (is (= (assoc strawberry-nutrition :KCalPer100g 42)
             (read-instance strawberry :entity :foo/Nutrition))))))

(deftest create-delete
  (create-fruits-table)   ; Make sure table is empty
  (testing "Create and delete within the transaction. There are no participants"
    (in-transaction
      {:on-commit (fn [participants actions]                    ; Specify the commit fn in-line
                    (is (empty? participants)))}
      (let [created (create strawberry)]
        (delete created)))))

(deftest commit-fn-no-more-txn
  (create-fruits-table)
  (testing "Cannot manipulate the transaction in the commit function"
    (in-transaction
      {:on-commit (fn [participants actions]                    ; Specify the commit fn in-line
                    (is (thrown? Exception (create banana))))}
      (create strawberry))))

(deftest delete-simple
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Delete something. Not then found inside txn or in the DB after commit"
    (in-transaction
      (let [managed-strawberry (read-instance strawberry)]
        (delete managed-strawberry)
        (is (in-deletion? managed-strawberry))
        (is (nil? (read-instance strawberry)))))
    (is (nil? (read-instance strawberry)))))

(deftest delete-recreate
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Delete and recreate within the transaction. The new instance is in the DB"
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)]
          (delete managed-strawberry)
          (is (in-deletion? managed-strawberry))
          (create long-life-strawberry)))
      (is (= long-life-strawberry (read-instance strawberry))))))

(deftest delete-recreate-in-child
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Delete and recreate in a child transaction. Inner transaction throws.
            Outer commits (because test consumes exception)"
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)]
          (delete managed-strawberry)
          (is (in-deletion? managed-strawberry))
          (is (thrown? Exception
                       (in-transaction
                         (create long-life-strawberry))))))
      (is (nil? (read-instance strawberry))))))

(deftest delete-recreate-twice
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Delete and recreate within the transaction. Create again. Throws."
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)]
          (delete managed-strawberry)
          (is (in-deletion? managed-strawberry))
          (create long-life-strawberry)
          (is (thrown? Exception (create managed-strawberry))))))))

(deftest create-twice
  (create-fruits-table)
  (testing "Create the same id twice. Throws."
    (in-transaction
      (create strawberry)                                   ; Note txn will commit because exception is swallowed by assertion
      (is (thrown? Exception (create strawberry))))))

(deftest create-already-managed
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Create something already read from the environment. Throws"
    (in-transaction
        (let [managed-strawberry (read-instance strawberry)]
          (is (thrown? Exception (create managed-strawberry)))))))

(deftest create-already-exists
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Create an id already in the backing store. Throws."
    (in-transaction
      (is (thrown? Exception (create (assoc strawberry :ShelfLife 21)))))))

(deftest mutate-deleted
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Try to mutate something already being deleted. Throws"
    (in-transaction
      (let [managed-strawberry (read-instance strawberry)]
        (delete managed-strawberry)
        (is (in-deletion? managed-strawberry))
        (is (thrown? Exception (assoc managed-strawberry :ShelfLife 21)))))))

(deftest delete-twice
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Delete something. Delete it again. Throws."
    (in-transaction
      (let [managed-strawberry (read-instance strawberry)]
        (delete managed-strawberry)
        (is (in-deletion? managed-strawberry))
        (is (thrown? Exception (delete managed-strawberry)))))))

(deftest mutate-parent-see-child
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Mutate something, then see mutated value in child txn and in DB after commit"
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)
              in-txn-strawberry (merge managed-strawberry long-life-strawberry)]
          (in-transaction
            (is (joined? managed-strawberry))
            (is (= long-life-strawberry (read-instance strawberry))))))
        (is (= long-life-strawberry (read-instance strawberry))))))

(deftest mutate-parent-touch-child
  (create-fruits-table)
  (create-nutrition-table)
  (write-instance strawberry)
  (testing "Mutate something, throws on any manipulation in child"
    (let [long-life-strawberry (assoc strawberry :ShelfLife 21)]
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)
              in-txn-strawberry (merge managed-strawberry long-life-strawberry)]
          (in-transaction
            (is (joined? managed-strawberry))
            (is (thrown? Exception (delete managed-strawberry)))
            (is (thrown? Exception (assoc managed-strawberry :ShelfLife 28)))
            (is (thrown? Exception (create strawberry))))))
      (is (= long-life-strawberry (read-instance strawberry))))))

(deftest delete-instance-delete-related
  (create-fruits-table)
  (create-fruits-supplier-table)
  (create-supplier-table)
  (write-instance kent-fruits)
  (write-instance sussex-fruits)
  (write-instance strawberry)
  (write-instance pineapple)
  (write-instance banana)
  (doall (map #(write-instance %1) strawberry-supplier-mappings))
  (testing "Delete a Fruit. Its associated FruitSupplier mappings go too"
    (in-transaction
      (let [managed-strawberry (read-instance strawberry)]
        (delete managed-strawberry)))
    (is (= {:Fruit nil}
           (a/aggregate {} :to :foo/Fruit :key-val {:Fruit "Strawberry"})))
    (is (empty? (read-instance {} :entity :foo/FruitSupplier :key :all)))))

(deftest managed-in-txn
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Read things in a transaction they are managed. Outside they are not."
    (let [agg-f (fn [] (a/aggregate {} :to :foo/Fruit :key-val {:Fruit "Strawberry"}))
          inst-f (fn [] (read-instance {:Fruit "Strawberry"} :entity :foo/Fruit))]
      (in-transaction
        (let [agg (agg-f)
              instance (inst-f)]
          (is (managed? instance))
          (is (managed? (get agg :Fruit)))))
      (is (not (managed? (inst-f))))
      (is (not (managed? (get (agg-f) :Fruit)))))))

(deftest cannot-change-id
  (create-fruits-table)
  (write-instance strawberry)
  (testing "Changing an id field is not allowed (trapped during commit)"
    (is (thrown? Exception
      (in-transaction
        (let [managed-strawberry (read-instance strawberry)]
          (assoc managed-strawberry :Fruit "Rambutan")))))))
