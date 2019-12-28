<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [entity-txn](#entity-txn)
    - [Identity](#identity)
    - [Opening a Transaction](#opening-a-transaction)
    - [Setting Transaction Defaults](#setting-transaction-defaults)
- [Instance Lifecycle](#instance-lifecycle)
    - [Managed Instances](#managed-instances)
    - [make-new-instance](#make-new-instance)
    - [create](#create)
    - [in-creation?](#in-creation)
    - [delete](#delete)
    - [assoc and merge](#assoc-and-merge)
    - [State Transitions](#state-transitions)
    - [Nested Transactions](#nested-transactions)
- [Transaction Cycle](#transaction-cycle)
    - [:do-on-start](#do-on-start)
    - [body](#body)
    - [commit](#commit)
    - [abort](#abort)
    - [:do-on-end](#do-on-end)
- [Locks](#locks)
- [entity-foo](#entity-foo)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# entity-txn

entity-txn is a simple library that manages the state of domain type
instances, which are maps or records, during a set of CRUD operations as
a transaction. It requires the following

+ a golden source/sink for domain instances
+ a type system that supports identity and lifecycle events

A protocol is provided to make these connections, and the library comes with
an implementation that bridges to entity-core.

Of course, it's not very Clojure-like to *update* or *delete* things, but there
are a lot of such applications out there and how you implement your domain model is
up to you, for example accretion of rows instead of deletion. There are places in `entity-txn`
where you can veto deletion, either globally or on a per-type basis.

## Identity

Each domain type must define its identity. This is one or more of the map
values from from an instance. Additionally, there must be included something
that makes the identity unique across all domain types. For example, if we have
two types, `Fruit` and `Nutrition`, instances might be:
```clojure
:foo/Fruit ; Fruit type
{:Fruit       "Strawberry"           ; identity field
 :Description "Soft Summer Fruit"
 :ShelfLife   14}

:foo/Nutrition ; Nutrition type
{:Fruit       "Strawberry"           ; identity field
 :KCalPer100g 28
 :Fat         0
 :Salt        0}
```
Both types define their identity as the `:Fruit` field. The type system must
provide some meta data to resolve this ambiguity, so the respective identities
could look like
```clojure
{:Fruit       "Strawberry"           ; identity field
 :entity      :foo/Fruit}
```
and
```
{:Fruit       "Strawberry"           ; identity field
 :entity      :foo/Nutrition}
```
## Opening a Transaction
Use the `in-transaction` macro, supplying transaction arguments and body:
```clojure
(in-transaction
  {:on-commit my-fn}
  ( ... ))
```
## Setting Transaction Defaults
Use `set-transaction-defaults` during startup to avoid supplying transaction arguments on
each use, for example:
```clojure
(set-transaction-defaults
  :events (entity-txn-events)   ; use entity-core as domain type system
  :on-commit (fn [participants actions] ; write the participating instances to the DB using a DB transaction
               (sql/with-transaction [*fruit-db*]
                                     (write-txn-state participants)))))
```
See the api documentation for a full ilst of options.
# Instance Lifecycle
## Managed Instances
Instances are obtained from the golden source by `(read-instance ...)`. Such
instances are marked as *managed*. `entity-txn` provides the vars `assoc`
and `merge` that maintain the original state of the instance and its current
state within the transaction. Using these functions the latest state of any
instance is maintained in the transaction ready for commit. Note
that `read-instance` will include the current state of any instance presently
mutated or deleted in the transaction: mutated intances will return the present value
and deleted instances will be excluded.

Instances are only managed when obtained inside a transaction. Otherwise `read-entity` returns
results from the golden source unmodified, `assoc` and `merge`
operate without affecting transaction state and `delete` will throw.
## make-new-instance
Use this function to create candidate instances that can be placed in the transaction
using `create`. Arguments will specify the domain type and any initial value.
## create
Use `create` to mark the instance for creation in the transaction.
The type system has the opportunity to validate, further initialise or create further
instances, presumably related in the domain model.
## in-creation?
It is an error to create the same identity twice. Use `in-creation?` passing an
instance or its identity if it is necessary to check for this amongst a sequence
of instances being processed.
## delete
This function will mark the instance for deletion. The type system is notified the instance is
being deleted and can, for example, delete related instances.
## assoc and merge
These functions will, on first use, record the original value and any subsequent
changes as the current value in the transaction. See further below for interaction with
the type system.
## State Transitions
Transaction state management allows the following

+ Create and delete the same thing - nothing happens
+ Delete and create the same thing - delete followed by create in the golden sink
+ Delete, create, delete - delete the original instance in the golden sink

## Nested Transactions
Transactions can be nested, and only the state in the current transaction is committed
when it closes. If a child transaction performs `read-instance` and the results include state
held in any ancestor then this state will be represented, however it is not permitted to further
manipulate such instances in the current transaction.
# Transaction Cycle
The stages of transaction execution are:
## :do-on-start
A function of zero arguments called before execution of the body. This may be used, for example,
to arbitrate for locks.
## body
Execute the body
## commit
If no exception is thrown in the body, a transaction enters the commit phase.

The type system is informed of each mutation, passing the original and current values. It then
has a chance to perform any further domain value changes, for example for reasons of domain model
constraints, silently veto the change by returning the old value, or veto the entire
transaction by throwing. If further state is entered into the transaction by
this means this process continues until there are no new participants.

Then, call the `:on-commit` function, passing the accumulated participants and their actions.
These arguments are maps:
```clojure
participants -> {<identity> <value>}
actions      -> {<identity> <action>}
```
The action is one of `:create`, `:mutate` or `:delete`. The value is the instance or, in the
case of `:mutate` a map containing `{:old-val <original> :new-val <current>}`. A convenience
function `write-txn-state` will perform all actions to the golden sink, as in the example, above.
## abort
If an exception occurs in the transaction body, all transaction state is discarded and
any `:on-abort` function is called.
## :do-on-end
If held, this function will be called whether the transaction was committed or aborted.
# Locks
The library includes a simple lock manager. If, for example, a transaction wishes to gain
exclusive access according to some domain value, `:do-on-start` might be
```clojure
(entitytxn.lock/lock some-value)
```
The lock manager can be used directly, in which case locks are not part of transaction state.
Locks taken out as above will be automatically released when the transaction closes.
# entity-foo
By now it will be clear that `entity-txn` and the other `entity-...` libraries are aimed
at bringing domain types, model management and I/O into the Clojure functional world.
See [`entity-core`](https://github.com/inqwell/entity-core)
and [`entity-sql`](https://github.com/inqwell/entity-sql) for further details

Often, many type instances must be brought together in a nested structure to support some
particular processing. This task is accomplished by `entity-core`'s `aggregate` function
and to ensure such instances are managed, there is `entitytxn.aggregate/aggregate`.

If you are not using entity-core and instead providing your own type system, you might find
it useful to provide your own such function. This is quite straightforward to do, for
example using the excellent [`specter`](https://github.com/nathanmarz/specter) library.

## Usage

`[entity/entity-txn "0.1.3"]`
