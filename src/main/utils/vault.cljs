(ns utils.vault
  (:require
   ["@pulumi/kubernetes" :as k8s]
   ["@pulumi/pulumi" :as pulumi]
   ["@pulumi/vault" :as vault]
   ["fs" :as fs]
   ["js-yaml" :as yaml]
   ["path" :as path]
   [configs :refer [cfg]]))

(defn get-secret-val
  "Extract a specific key from a Vault secret Output/Promise."
  [secret-promise key]
  (.then secret-promise #(aget (.-data %) key)))

(defn initialize-mount [vault-provider vault-path service-name]
  (let [service-secrets (into {} (get (-> cfg :secrets-json) (keyword service-name)))]
    (new (.. vault -generic -Secret)
         (str service-name "-secret")
         (clj->js {:path (str vault-path)
                   :dataJson (js/JSON.stringify (clj->js service-secrets))})
         (clj->js {:provider vault-provider}))))

(defn prepare
  "Prepares common resources and values for a deployment from a single config map."
  [config]
  (let [{:keys [provider vault-provider app-name app-namespace load-yaml]} config
        values-path (.join path js/__dirname ".." (-> cfg :resource-path) (str app-name ".yml"))]

    (let [yaml-values (when load-yaml
                        (js->clj (-> values-path
                                     (fs/readFileSync "utf8")
                                     (yaml/load))
                                 :keywordize-keys true))
          {:keys [secrets-data bind-secrets]}
          (when vault-provider
            (let [vault-path (str "secret/" app-name)
                  _ (initialize-mount vault-provider vault-path app-name)
                  secrets (pulumi/output (.getSecret (.-generic vault)
                                                     (clj->js {:path vault-path})
                                                     (clj->js {:provider vault-provider})))
                  secrets-data (.apply secrets #(.. % -data))
                  bind-secrets (when (and provider app-namespace)
                                 (new (.. k8s -core -v1  -Secret) (str app-name "-secrets")
                                      (clj->js {:metadata {:name (str app-name "-secrets")
                                                           :namespace app-namespace}
                                                :stringData secrets-data})
                                      (clj->js {:provider provider})))]
              {:secrets-data secrets-data
               :bind-secrets bind-secrets}))]

    {:secrets secrets-data
     :yaml-path values-path
     :yaml-values yaml-values
     :bind-secrets bind-secrets})))


(defn retrieve [vault-provider app-name]
  (let [vault-path (str "secret/" app-name)
        secrets (pulumi/output (.getSecret (.-generic vault)
                                           (clj->js {:path vault-path})
                                           (clj->js {:provider vault-provider})))
        secrets-data (.apply secrets #(.. % -data))]
    {:secrets secrets-data}))


(def provider-template
  {:constructor (.. vault -Provider)
   :name "vault-provider"
   :config {:address 'vaultAddress
            :token   'vaultToken}})
