(ns pulumicljs.providers.k8s.behavior
  (:require ["@pulumi/kubernetes" :as k8s]))


(def provider-template
  {:constructor (.. k8s -Provider)
   :name "k8s-provider"
   :config {:kubeconfig 'kubeconfig}})


(defn pre-deploy-rule
  "k8s pre-deploy rule: scans the service registry and creates
   all unique namespaces. Returns a map of created namespaces
   keyed by their name."
  [{:keys [resource-configs provider]}]
  (let [namespaces (->> resource-configs
                        (remove #(contains? % :no-namespace))
                        (map :app-namespace)
                        (remove nil?)
                        (set))]
    (into {}
          (for [ns-name namespaces]
            (let [resource-name ns-name
                  ns-config {:metadata {:name resource-name
                                        :namespace ns-name}}
                  ns-resource (new (.. k8s -core -v1 -Namespace) resource-name
                                   (clj->js ns-config)
                                   (clj->js {:provider provider}))]
              [ns-name ns-resource])))))