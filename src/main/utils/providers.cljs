(ns utils.providers
  (:require
   ["@pulumi/pulumi" :as pulumi] ["@pulumi/vault" :as vault] ["@pulumiverse/harbor" :as harbor] ["@pulumi/kubernetes" :as k8s]
   [clojure.string :as str] [clojure.walk :as walk]
   [utils.general :refer [resolve-template]]
   [utils.k8s :as k8s-utils]
   [utils.harbor :as harbor-utils]
   [utils.docker :as docker-utils] [utils.vault :as vault-utils]
   [utils.stack-processor :refer [deploy! component-specs]]))

(defn resolve-provider-template [constructor name config]
  {:constructor constructor
   :name name
   :config config})

(def provider-templates
  (into {} (map (fn [[k v]] [k (apply resolve-provider-template (vals v))])
                {:vault vault-utils/provider-template
                 :harbor harbor-utils/provider-template
                 :k8s k8s-utils/provider-template})))

(defn get-provider-outputs-config []
  {:vault  {:stack :init
            :outputs ["vaultAddress" "vaultToken"]}
   :harbor {:stack :shared
            :outputs ["username" "password" "url"]}
   :k8s   {:stack :init
           :outputs ["kubeconfig"]}})


#_(defn get-stack-refs []
  {:init (new pulumi/StackReference "init") 
   :shared (new pulumi/StackReference "shared")})

(defn get-stack-refs [stack-ref-array]
  (into {}
        (map (fn [stack-name]
               [(keyword stack-name)
                (new pulumi/StackReference stack-name)])
             stack-ref-array)))

(defn extract-expanded-keywords [stack]
  (let [expand-chain
        (fn [chain]
          (when (and (sequential? chain) (keyword? (first chain)))
            (let [ns (or (namespace (first chain)) (name (first chain)))]
              (map #(keyword ns (name %)) (rest chain)))))]

    (mapcat (fn [item]
              (cond
                (and (sequential? item) (keyword? (first item)))
                (expand-chain item)
                (keyword? item)
                [item]
                :else
                nil))
            stack)))



(defn get-all-providers [resource-configs]
  (->> resource-configs
       (mapcat (comp extract-expanded-keywords :stack))

       (map (fn [component-key]
              (if-let [ns (namespace component-key)]
                (keyword ns)
                (let [k-name (name component-key)
                      parts (str/split k-name #":")]
                  (when (> (count parts) 1)
                    (keyword (first parts)))))))
       (remove nil?)
       (into #{})
       vec))

(def provider-rules
  {:k8s k8s-utils/pre-deploy-rule})


(defn provider-apply [stack-resources-definition pulumi-cfg]
  (let [providers-needed (get-all-providers (:resource-configs stack-resources-definition))
        provider-outputs-config (:provider-external-inputs stack-resources-definition)
        stack-refs (get-stack-refs (:stack-references stack-resources-definition))
        needed-output-configs (select-keys provider-outputs-config providers-needed)
        ;; At some point we should add the ability for Providers to be passed Pulumi configs or our config map?
        ;; Cloudflare and others may require or request a token.
        outputs-to-fetch (reduce-kv
                          (fn [acc _provider-key data]
                            (let [stack-key (:stack data)
                                  stack-ref (get stack-refs stack-key)
                                  outputs (:outputs data)]

                              (reduce
                               (fn [m output-name]
                                 (assoc m (keyword output-name) (.getOutput stack-ref output-name)))
                               acc
                               outputs)))
                          {}
                          needed-output-configs)

        all-provider-inputs (pulumi/all (clj->js outputs-to-fetch))]

    (.apply all-provider-inputs
            (fn [values]
              (js/Promise.
               (fn [resolve _reject]
                 (let [resolved-outputs (js->clj values :keywordize-keys true)
                       instantiated-providers
                       (reduce
                        (fn [acc provider-key]
                          (if-let [template (get provider-templates provider-key)]
                            (let [constructor (:constructor template)
                                  provider-name (:name template)
                                  resolved-config (resolve-template (:config template) {} resolved-outputs)]

                              (assoc acc provider-key (new constructor provider-name (clj->js resolved-config))))
                            acc))
                        {}
                        providers-needed)
                       pre-deploy-results
                       (reduce-kv
                        (fn [acc provider-key provider-instance]
                          (if-let [rule-fn (get provider-rules provider-key)]
                            (let [rule-results (rule-fn {:resource-configs (:resource-configs stack-resources-definition)
                                                         :provider provider-instance})]
                              (assoc acc provider-key rule-results))
                            acc))
                        {}
                        instantiated-providers)]
                   (resolve
                    (deploy!
                     {:pulumi-cfg pulumi-cfg
                      :resource-configs (:resource-configs stack-resources-definition)
                      :all-providers instantiated-providers
                      :pre-deploy-deps pre-deploy-results})))))))))