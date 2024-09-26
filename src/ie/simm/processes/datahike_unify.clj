(ns ie.simm.processes.datahike-unify
  (:require [datahike.api :as d]))

;; Schema Definition
(def schema
  [{:db/ident :type/const
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :type/type-app
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :type/args
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :type/unbound
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :type/link
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :type/generic
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   ;; Schema for Function Types
   {:db/ident :type/fn
    :db/valueType :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident :fn/params
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :fn/ret-type
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; Initialize Datomic connection
(defn initialize-db [cfg]
  (let [cfg  (d/create-database cfg)
        conn (d/connect cfg)]
    (d/transact conn {:tx-data schema})
    conn))

;; Define the unification rules
(def unify-rules
  '[;; Resolve any links for a type recursively
    [(resolve-link ?x ?final)
     [(not [?x :type/link _])] ;; No link means ?x is the final type ;; TODO this  syntax does not match Datomic
     [(identity ?x) ?final]]   ;; The type is itself

    [(resolve-link ?x ?final)
     [?x :type/link ?y]        ;; If there is a link, follow it
     (resolve-link ?y ?final)] ;; Recurse until we find the final type

    ;; Ensure constants are equal
    [(unify-const ?t ?o)
     [?t :type/const ?const]     ;; Both must have the same constant value
     [?o :type/const ?const]]

    ;; Unify type applications (main type and args)
    [(unify-type-app ?t ?o)
     [?t :type/type-app ?main]   ;; Both must share the same main type
     [?o :type/type-app ?main]   ;; Use the same logic variable ?main to unify

     [?t :type/args ?t-args]
     [?o :type/args ?o-args]
     (coll-equal ?t-args ?o-args)] ;; Recursively unify arguments

    ;; Unify function types (params and return types)
    [(unify-fn ?t ?o)
     [?t :fn/params ?params]    ;; Unify the parameters
     [?o :fn/params ?params]    ;; Same logic variable ?params unifies them

     [?t :fn/ret-type ?ret]     ;; Unify the return type
     [?o :fn/ret-type ?ret]]   ;; Same logic variable ?ret unifies them

    ;; Recursively compare collections
    [(coll-equal ?a ?b)
     [(= (empty? ?a) (empty? ?b))]]

    [(coll-equal ?a ?b)
     [(first ?a) ?a-head]
     [(rest ?a) ?a-tail]
     [(first ?b) ?a-head]
     [(rest ?b) ?b-tail]
     (coll-equal ?a-tail ?b-tail)] ;; Recursively unify tails
    ])

;; Unification query that leverages the above rules
(defn unify-query [db t o]
  (d/q '[:find ?t-final ?o-final
         :in $ % ?t ?o
         :where
         ;; Resolve any links first
         (resolve-link ?t ?t-final)
         (resolve-link ?o ?o-final)

         (or
         ;; Try to unify constants
          (unify-const ?t-final ?o-final)

         ;; Try to unify type applications
          (unify-type-app ?t-final ?o-final)

         ;; Try to unify function types
          (unify-fn ?t-final ?o-final))]
       db unify-rules t o))

;; Handle unbound types by linking them in Datomic
(defn handle-unbound! [conn t-final o-final]
  (let [db (d/db conn)
        ;; Check if either t or o is unbound
        t-unbound (first (d/q '[:find ?id
                                :in $ ?type
                                :where
                                [?type :type/unbound ?id]] db t-final))
        o-unbound (first (d/q '[:find ?id
                                :in $ ?type
                                :where
                                [?type :type/unbound ?id]] db o-final))]
    (cond
      ;; If t is unbound, link it to o
      t-unbound (d/transact conn [{:db/id t-final :type/link o-final}])

      ;; If o is unbound, link it to t
      o-unbound (d/transact conn [{:db/id o-final :type/link t-final}]))))

;; Main unification function
(defn unify! [conn t o]
  (let [db (d/db conn)
        result (first (unify-query db t o))]
    (if result
      (let [[t-final o-final] result]
        ;; Handle unbound variables (linking them)
        (handle-unbound! conn t-final o-final)
        (println "Unification successful for:" t-final o-final))
      (throw (ex-info "Unification failed" {:type :unification-failed :result result})))))

;; TODO replace with squuid from datahike
(defn squuid []
  (.getTime (java.util.Date.)))

;; Helper to create a new unbound variable type
(defn new-var! [conn]
  (let [new-id (d/tempid :db.part/user)]
  (->
    (d/transact conn [{:db/id new-id
                       :type/unbound (squuid)}])
    :tempids
    (get new-id))))

;; Helper to create a constant type
(defn new-const! [conn value]
  (let [new-id (d/tempid :db.part/user)]
    (-> (d/transact conn [{:db/id new-id
                           :type/const value}])
        :tempids
        (get new-id))))

;; Helper to create a function type
(defn new-fn-type! [conn params ret-type]
  (let [fn-id (d/tempid :db.part/user)]
  (->
   (d/transact conn [{:db/id fn-id
                      :type/fn true
                      :fn/params params
                      :fn/ret-type ret-type}])
   :tempids
   (get fn-id))))

;; Helper to create a function application type
(defn new-type-app! [conn main args]
  (let [type-id (d/tempid :db.part/user)]
  (->
   (d/transact conn [{:db/id type-id
                      :type/type-app main
                      :type/args args}])
   :tempids
   (get type-id))))

(defn instantiate-fn-type! [db n-params]
  (let [params (repeatedly n-params #(new-var! db))
        ret-type (new-var! db)]
    (new-fn-type! db params ret-type)))


(defn match-fn-type! [conn n-params type]
  (let [db (d/db conn)]
    (if-let [[params ret-type] (first (d/q '[:find ?params ?ret-type
                                             :in $ ?fn-type
                                             :where
                                             [?fn-type :fn/params ?params]
                                             [?fn-type :fn/ret-type ?ret-type]]
                                           db type))]
      ;; If the type is already a function, check parameter counts and return type
      (do (assert (= (count params) n-params) "Unexpected number of arguments")
          [params ret-type])

      ;; If unbound, instantiate a new function type with n-params arguments
      (if-let [unbound (first (d/q '[:find ?unbound
                                     :in $ ?type
                                     :where
                                     [?type :type/unbound ?unbound]]
                                   db type))]
        (instantiate-fn-type! conn n-params)
        (throw (Exception. (str "Type is not a function: " type)))))))

(defn infer! [conn env expr]
  (match expr
    ;; Variable lookup
    [:var v-name]
    (if-let [t (env v-name)]
      (instantiate conn t)  ;; Ensure instantiation of type variables
      (throw (Exception. (str "Variable " v-name " not found"))))

    ;; Function creation (lambda)
    [:fun params body]
    (let [param-types (mapv (fn [_] (new-var! conn)) params)
          fn-env (merge env (zipmap params param-types))
          ret-type (infer! conn fn-env body)]
      (new-fn-type! conn param-types ret-type))

    ;; Function application
    [:call fnn args]
    (let [[param-types ret-type] (match-fn-type! conn (count args) (infer! conn env fnn))]
      (doseq [[p a] (map vector param-types args)]
        (unify! conn p (infer! conn env a))) ;; Unify each argument with the corresponding parameter
      ret-type)))



(comment
;; Initialize the in-memory database
  (def conn (initialize-db {}))

;; Create a few constants and variables
  (def const1 (new-const! conn "int"))

  (def const2 (new-const! conn "int"))

  (def const3 (new-const! conn "string"))

  (def var1 (new-var! conn))

  (def var2 (new-var! conn))

;; Unifying two identical constants should succeed
  (unify! conn const1 const2)  ;; This should succeed without issues

  (unify-query (d/db conn) const1 const2)

;; Trying to unify different constants should fail
  (try
    (unify! conn const1 const3)
    (catch Exception e (println e)))  ;; This should throw an exception

  ;; add a function type taking var1 in and returning const1
  (def fn1 (new-fn-type! conn [const2] const1))

  (def app1 (new-type-app! conn fn1 [var2]))

  (unify! conn var2 const2)

  (unify-query (d/db conn) var2 const2)

  ;; Define a function type: (Int -> Bool)
  (def fn-id (new-fn-type! conn [(new-var! conn)] (new-var! conn)))

  ;; Example of a function call with arguments
  (infer! conn {} [:call fn-id [const1]])



  )