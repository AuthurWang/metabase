(ns metabase.models.query.permissions
  "Functions used to calculate the permissions needed to run a query based on old-style DATA ACCESS PERMISSIONS. The
  only thing that is subject to these sorts of checks are *ad-hoc* queries, i.e. queries that have not yet been saved
  as a Card. Saved Cards are subject to the permissions of the Collection to which they belong."
  (:require [clojure.tools.logging :as log]
            [metabase.api.common :as api]
            [metabase.models
             [interface :as i]
             [permissions :as perms]
             [table :refer [Table]]]
            [metabase.query-processor.util :as qputil]
            [metabase.util :as u]
            [metabase.util
             [i18n :refer [tru]]
             [schema :as su]]
            [schema.core :as s]
            [toucan.db :as db]))

;;; ---------------------------------------------- Permissions Checking ----------------------------------------------

;; Is calculating permissions for queries complicated? Some would say so. Refer to this handy flow chart to see how
;; things get calculated.
;;
;;                   perms-set
;;                        |
;;                        |
;;                        |
;;   native query? <------+-----> mbql query?
;;         ↓                           ↓
;; adhoc-native-query-path     mbql-perms-path-set
;;                                      |
;;                no source card <------+----> has source card
;;                        ↓                          ↓
;;          tables->permissions-path-set   source-card-read-perms

(defn- query->source-and-join-tables
  "Return a sequence of all Tables (as TableInstance maps, or IDs) referenced by `query`."
  [{:keys [source-table join-tables source-query native], :as query}]
  (cond
    ;; if we come across a native query just put a placeholder (`::native`) there so we know we need to add native
    ;; permissions to the complete set below.
    native       [::native]
    ;; if we have a source-query just recur until we hit either the native source or the MBQL source
    source-query (recur source-query)
    ;; for root MBQL queries just return source-table + join-tables
    :else        (cons source-table (map :table-id join-tables))))

(s/defn ^:private tables->permissions-path-set :- #{perms/ObjectPath}
  "Given a sequence of `tables` referenced by a query, return a set of required permissions."
  [database-or-id :- (s/cond-pre su/IntGreaterThanZero su/Map) tables]
  (let [table-ids        (filter integer? tables)
        table-id->schema (when (seq table-ids)
                           (db/select-id->field :schema Table :id [:in table-ids]))]
    (set (for [table tables]
           (cond
             ;; Any `::native` placeholders from above mean we need native ad-hoc query permissions for this DATABASE
             (= ::native table)
             (perms/adhoc-native-query-path database-or-id)

             ;; If Table is an ID then fetch its schema from the DB and require normal table perms
             ;; TODO - we should check and see if Table is in the QP store here so we don't do the extra fetch
             (integer? table)
             (perms/object-path (u/get-id database-or-id) (table-id->schema table) table)

             ;; for a TableInstance require normal table perms
             :else
             (perms/object-path (u/get-id database-or-id)
                                (:schema table)
                                ;; TODO - don't think we use `:table-id` anywhere anymore
                                (or (:id table) (:table-id table))))))))

(s/defn ^:private source-card-read-perms :- #{perms/ObjectPath}
  "Calculate the permissions needed to run an ad-hoc query that uses a Card with `source-card-id` as its source
  query."
  [source-card-id :- su/IntGreaterThanZero]
  (i/perms-objects-set (or (db/select-one ['Card :collection_id] :id source-card-id)
                           (throw (Exception. (str (tru "Card {0} does not exist." source-card-id)))))
                       :read))

(defn- preprocess-query [query]
  ;; ignore the current user for the purposes of calculating the permissions required to run the query. Don't want the
  ;; preprocessing to fail because current user doesn't have permissions to run it when we're not trying to run it at
  ;; all
  (binding [api/*current-user-id* nil]
    ((resolve 'metabase.query-processor/preprocess) query)))

;; TODO - not sure how we can prevent circular source Cards if source Cards permissions are just collection perms now???
(s/defn ^:private mbql-permissions-path-set :- #{perms/ObjectPath}
  "Return the set of required permissions needed to run an adhoc `query`.

  Also optionally specify `throw-exceptions?` -- normally this function avoids throwing Exceptions to avoid breaking
  things when a single Card is busted (e.g. API endpoints that filter out unreadable Cards) and instead returns 'only
  admins can see this' permissions -- `#{\"db/0\"}` (DB 0 will never exist, thus normal users will never be able to
  get permissions for it, but admins have root perms and will still get to see (and hopefully fix) it)."
  [query :- {:query su/Map, s/Keyword s/Any} & [throw-exceptions?     :- (s/maybe (s/eq :throw-exceptions))
                                                already-preprocessed? :- (s/maybe (s/eq :already-preprocessed))]]
  (try
    ;; if we are using a Card as our perms are that Card's (i.e. that Card's Collection's) read perms
    (if-let [source-card-id (qputil/query->source-card-id query)]
      (source-card-read-perms source-card-id)
      ;; otherwise if there's no source card then calculate perms based on the Tables referenced in the query
      (let [{:keys [query database]} (cond-> query
                                       (not already-preprocessed?) preprocess-query)]
        (tables->permissions-path-set database (query->source-and-join-tables query))))
    ;; if for some reason we can't expand the Card (i.e. it's an invalid legacy card) just return a set of permissions
    ;; that means no one will ever get to see it (except for superusers who get to see everything)
    (catch Throwable e
      (when throw-exceptions?
        (throw e))
      (log/warn (tru "Error calculating permissions for query: {0}" (.getMessage e))
                "\n"
                (u/pprint-to-str (u/filtered-stacktrace e)))
      #{"/db/0/"})))                    ; DB 0 will never exist

(s/defn perms-set :- #{perms/ObjectPath}
  "Calculate the set of permissions required to run an ad-hoc `query`."
  {:arglists '([outer-query & [throw-exceptions? already-preprocessed?]])}
  ;; TODO - I think we can remove the `throw-exceptions?` optional param because nothing uses it anymore
  [{query-type :type, database :database, :as query} & [throw-exceptions?     :- (s/maybe (s/eq :throw-exceptions))
                                                        already-preprocessed? :- (s/maybe (s/eq :already-preprocessed))]]
  (cond
    (empty? query)                   #{}
    (= (keyword query-type) :query)  (mbql-permissions-path-set query throw-exceptions? already-preprocessed?)
    (= (keyword query-type) :native) #{(perms/adhoc-native-query-path database)}
    :else                            (throw (Exception. (str (tru "Invalid query type: {0}" query-type))))))
