(ns pulumicljs.processors.stack-processor
  (:require
   [pulumicljs.utils.general :refer [deep-merge p-> coerce-value p-threading p-lift]]
   [pulumicljs.utils.safe-fns :refer [safe-fns]]
   [pulumicljs.processors.config-processor :as proc]
   [clojure.walk :as walk]
   [pulumicljs.utils.pulumi :as pulumi]
   [clojure.string :as str]
   )

)



(defn generic-transform
  "Takes a creator function and executes it with resolved arguments,
   handling asynchronicity when secrets are present."
  [creator-fn resource-opts base-values options]
  (pulumi/apply-output options
                       #(creator-fn (deep-merge base-values (proc/resolve-template resource-opts %)))))


(defmulti deploy-resource
  "Generic resource deployment multimethod.
   Dispatches on the fully-qualified resource keyword.
   Returns a map of {:resource (the-pulumi-resource) :common-opts-update {map-of-new-state}}."
  (fn [component-specs dispatch-key _config] dispatch-key))

(defmethod deploy-resource :default
  [component-specs dispatch-key full-config]
  (if-let [spec (get component-specs dispatch-key)]
    (let [ctx           (:cumulative-options full-config)
          app-name      (:app-name ctx)
          provider-key  (:provider-key spec)
          provider      (get full-config provider-key)
          pulumi-opts   (:pulumi-options (:resource-config full-config)) 
          resource-class (:constructor spec)
          component-opts (:component-opts (:resource-config full-config))

          env {:cumulative-opts ctx
               :component-opts      component-opts
               :options             (merge ctx component-opts pulumi-opts)}
          raw-defaults  (when-let [df (:default-fn spec)] (df env))]

      (if resource-class
        (let [base-creator (fn [final-args suffix]
                             (let [final-name (if suffix (str app-name "-" suffix) app-name)]
                               (pulumi/new-resource resource-class
                                                    final-name
                                                    final-args
                                                    (cond-> pulumi-opts
                                                      provider (assoc :provider provider)))))]


          {:resource
           (p-> raw-defaults
                #(let [defaults-list (if (vector? %) % [%])
                       is-multi?     (vector? %)
                       resources
                       (doall
                        (map-indexed
                         (fn [idx item]
                           (let [suffix (cond (:_suffix item) (:_suffix item)
                                              is-multi? (str idx)
                                              :else nil)
                                 clean-item (dissoc item :_suffix)]
                             (generic-transform (fn [args] (base-creator args suffix))
                                                component-opts
                                                clean-item
                                                ctx)))
                         defaults-list))]
                   (if is-multi? resources (first resources))))}
          #_{:resource
           (p-> raw-defaults
                #(let [defaults-list (if (vector? %) % [%])
                       is-multi?     (vector? %)
                       resources
                       (doall
                        (map-indexed
                         (fn [idx item]
                           (let [suffix (cond (:_suffix item) (:_suffix item)
                                              is-multi? (str idx)
                                              :else nil)
                                 clean-item (dissoc item :_suffix)]
                             (generic-transform (fn [args] (base-creator args suffix))
                                                component-opts
                                                clean-item
                                                ctx)))
                         defaults-list))]
                   (if is-multi? resources (first resources))))})
        (throw (ex-info (str "No :constructor found for " dispatch-key) {}))))
    (throw (ex-info (str "Unknown resource: " dispatch-key) {}))))



(defn merge-into-stack-entry
  [obj target-key merge-path merge-map]
  (let [stack (:stack obj)
        idx   (some (fn [[i m]]
                      (when (contains? m target-key)
                        i))
                    (map-indexed vector stack))]
    (if (nil? idx)
      obj
      (update-in obj (into [:stack idx] merge-path)
                 merge merge-map))))




(defn count-resource-types [stack-items]
  ;; flatten handles nested vectors (groups) automatically
  (frequencies (keep get-item-type (flatten stack-items))))

(defn get-item-type [item]
  (cond
    (keyword? item) item
    (map? item)     (let [reserved #{:alias :pulumi-options}]
                      (some #(when-not (contains? reserved %) %) (keys item)))
    :else nil))




(defn handle-keyword-item [last-resource component-specs dispatch-key final-config common-opts]
  (let [;; 1. Get user-defined options
        user-opts (get-in final-config [:stack 0 :pulumi-options] {})
        deps (cond-> (get user-opts :dependsOn [])
               last-resource (conj last-resource))
        merged-pulumi-opts (assoc user-opts :dependsOn deps)

        deploy-config (-> final-config
                          (assoc-in [:resource-config :pulumi-options] merged-pulumi-opts)
                          (assoc :cumulative-options common-opts))


        _ (reset! debug-log [])
        _ (swap! debug-log conj deploy-config) 

        result-map    (deploy-resource component-specs dispatch-key deploy-config)
        resource      (:resource result-map)]

    [resource
     nil
     (merge common-opts (:common-opts-update result-map))]))







(defn handle-list-item [last-resource item config common-opts]
  (let [provider-key (first item)
        resource-items (rest item)]
    (reduce
     (fn [nested-acc resource-item]
       (let [{:keys [last-resource resources common-opts]} nested-acc

             type-kw (get-item-type resource-item)
             dispatch-key (keyword (name provider-key) (name type-kw))

             inner-config (-> config
                              (assoc :cumulative-options common-opts)
                              (merge (get resource-item type-kw))
                              (assoc :stack [resource-item]))

             [new-resource _ new-common-opts]
             (handle-keyword-item last-resource component-specs dispatch-key inner-config common-opts)]

         {:last-resource new-resource
          :resources     (assoc resources dispatch-key new-resource)
          :common-opts   new-common-opts}))
     {:last-resource last-resource
      :resources     {}
      :common-opts   common-opts}
     resource-items)))

(defn derive-identity [type-kw config resources-map type-counts]
  (let [raw-alias     (:alias config)

        _ (when (and (string? raw-alias) (str/blank? raw-alias))
            (throw (ex-info "Resource alias cannot be an empty string."
                            {:type type-kw :config config})))

        user-alias    raw-alias
        resource-name (or (:name config) (get-in config [:metadata :name]))

        total-count   (get type-counts type-kw 0)
        is-multi?     (> total-count 1)

        proposed-key
        (cond
          user-alias
          user-alias

          resource-name
          (keyword (name type-kw) (str resource-name))

          is-multi?
          (throw (ex-info (str "Ambiguous resource: " type-kw ". "
                               "There are " total-count " resources of this type. "
                               "ALL must have a unique :alias or :name.")
                          {:type type-kw :count total-count}))

          :else
          type-kw)]

    (if (contains? resources-map proposed-key)
      (throw (ex-info (str "Duplicate resource identity detected: " proposed-key)
                      {:key proposed-key :config config}))
      proposed-key)))



(defn process-stack [stack-items config initial-common-opts]
  (let [type-counts (count-resource-types stack-items)]

    (reduce
     (fn [acc item]
       (let [{:keys [last-resource resources-map common-opts]} acc]

         (cond
           (vector? item)
           (let [[new-res new-map new-opts]
                 (handle-list-item last-resource component-specs item config common-opts)]
             {:last-resource (or new-res last-resource)
              :resources-map (merge resources-map new-map)
              :common-opts   new-opts})

           :else
           (let [reserved-keys #{:alias :pulumi-options}
                 [type-kw specific-config]
                 (cond
                   (keyword? item) [item {}]
                   (map? item)     (let [k (some #(when-not (contains? reserved-keys %) %) (keys item))]
                                     [k (get item k)])
                   :else (throw (ex-info "Unknown item" {:item item})))

                 resource-for-identity (cond-> {:component-opts specific-config}
                                       (:alias item) (assoc :alias (:alias item))
                                       (:pulumi-options item)
                                       (assoc :pulumi-options (:pulumi-options item)))

                 storage-key (derive-identity type-kw
                                              resource-for-identity
                                              resources-map
                                              type-counts)

                 resource-config (merge {:resource-config resource-for-identity}
                                        {:stack (:stack config)})



                 [new-res _ result-opts]
                 (handle-keyword-item last-resource
                                      component-specs
                                      type-kw
                                      resource-config
                                      common-opts)]

             {:last-resource (or new-res last-resource)
              :resources-map (assoc resources-map storage-key new-res)
              :common-opts   (merge common-opts result-opts)}))))

     {:last-resource nil :resources-map {} :common-opts initial-common-opts}
     stack-items)))




#_(defn deploy! [{:keys [pulumi-cfg resource-configs all-providers]}]
  (let [deployment-results
        (into
         {}
         (for [config resource-configs]
           (let [{:keys [stack app-name]} config
;;                 _ (when (nil? config)
  ;;                   (throw (js/Error. "Resource configs contain a nil value!")))

                 common-opts (merge
                              all-providers
                              (select-keys config [:app-name :app-namespace])
                              {:pulumi-cfg pulumi-cfg})]

             [app-name (process-stack stack config common-opts)])))]
    (clj->js deployment-results)))
