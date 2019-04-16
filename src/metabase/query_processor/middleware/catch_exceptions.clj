(ns metabase.query-processor.middleware.catch-exceptions
  "Middleware for catching exceptions thrown by the query processor and returning them in a friendlier format."
  (:require [metabase.util :as u]
            schema.utils)
  (:import clojure.lang.ExceptionInfo
           [schema.utils NamedError ValidationError]))

(def ^:dynamic ^:private *add-preprocessed-queries?* true)

(defmulti ^:private fail
  "Format an Exception thrown by the Query Processor."
  {:arglists '([query e])}
  (fn [_ e]
    (class e)))

(defmethod fail Throwable
  [{query-type :type, :as query}, ^Throwable e]
  (merge
   {:status     :failed
    :class      (class e)
    :error      (or (.getMessage e) (str e))
    :stacktrace (u/filtered-stacktrace e)
    ;; TODO - removing this stuff is not really needed anymore since `:database` is just the ID and not the
    ;; entire map including `:details`
    :query      (dissoc query :database :driver)}
   ;; add the fully-preprocessed and native forms to the error message for MBQL queries, since they're extremely
   ;; useful for debugging purposes. Since generating them requires us to recursively run the query processor,
   ;; make sure we can skip adding them if we end up back here so we don't recurse forever
   (when (and (= (keyword query-type) :query)
              *add-preprocessed-queries?*)
     ;; obviously we do not want to get core.async channels back for preprocessed & native, so run the preprocessing
     ;; steps synchronously
     (let [query (dissoc query :async?)]
       (binding [*add-preprocessed-queries?* false]
         {:preprocessed (u/ignore-exceptions
                          ((resolve 'metabase.query-processor/query->preprocessed) query))
          :native       (u/ignore-exceptions
                          ((resolve 'metabase.query-processor/query->native) query))})))))


(defn- explain-schema-validation-error
  "Return a nice error message to explain the schema validation error."
  [error]
  (cond
    (instance? NamedError error)
    (let [nested-error (.error ^NamedError error)]
      ;; recurse until we find the innermost nested named error, which is the reason
      ;; we actually failed
      (if (instance? NamedError nested-error)
        (recur nested-error)
        (or (when (map? nested-error)
              (explain-schema-validation-error nested-error))
            (.name ^NamedError error))))

    (map? error)
    (first (for [e     (vals error)
                 :when (or (instance? NamedError e)
                           (instance? ValidationError e))
                 :let  [explanation (explain-schema-validation-error e)]
                 :when explanation]
             explanation))

    ;; When an exception is thrown, a ValidationError comes back like
    ;;    (throws? ("foreign-keys is not supported by this driver." 10))
    ;; Extract the message if applicable
    (instance? ValidationError error)
    (let [explanation (schema.utils/validation-error-explain error)]
      (or (when (list? explanation)
            (let [[reason [msg]] explanation]
              (when (= reason 'throws?)
                msg)))
          explanation))))

(defmethod fail ExceptionInfo
  [query e]
  (let [{error :error, error-type :type, :as data} (ex-data e)]
    (merge
     ((get-method fail Throwable) query e)
     (when-let [error-msg (and (= error-type :schema.core/error)
                               (explain-schema-validation-error error))]
       {:error error-msg})
     {:ex-data (dissoc data :schema)})))


(defn catch-exceptions
  "Middleware for catching exceptions thrown by the query processor and returning them in a normal format."
  [qp]
  ;; We are ignoring the default exception handler on purpose here. `catch-exceptions` middleware is the middleware
  ;; that's expected to handle any exceptions thrown by the QP, then wrap them up and pass them to `respond`; the
  ;; value of `raise` passed in initially is a placeholder that will complain about unhandled Exceptions
  (fn [query respond _ canceled-chan]
    (let [raise (fn [e]
                  (respond (fail query e)))]
      (try
        (qp query respond raise canceled-chan)
        (catch Throwable e
          (raise e))))))
