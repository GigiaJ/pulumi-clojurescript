 (ns pulumicljs.providers.k8s.defaults
    #?(:cljs (:require ["@pulumi/kubernetes" :as k8s])))
 

(def pvc
  {:path ['-core '-v1 '-PersistentVolumeClaim]
   :default-fn
   (fn [env] (let [{:keys [app-name app-namespace size storage-class access-mode]} (:options env)]
     {:apiVersion "v1"
      :kind "PersistentVolumeClaim"
      :metadata {:name (str app-name "-pvc")
                 :namespace app-namespace}
      :spec {:accessModes [(or access-mode "ReadWriteOnce")]
             :storageClassName (or storage-class "hcloud-volumes")
             :resources {:requests {:storage (or size "10Gi")}}}}))})

(def ingress
  {:path ['-networking '-v1 '-Ingress]
   :default-fn
   (fn [env] (let [{:keys [app-name app-namespace host]} (:options env)]
               {:metadata {:name app-name
                           :namespace app-namespace}
                :spec {:ingressClassName "caddy"
                       :rules [{:host host
                                :http {:paths [{:path "/"
                                                :pathType "Prefix"
                                                :backend {:service {:name app-name
                                                                    :port {:number 80}}}}]}}]}}))})

(def chart
  {:path ['-helm '-v3 '-Chart]
   :default-fn (fn [env]
                   (let [{:keys [app-name app-namespace]} (:options env)]
                     {:chart     app-name
                      :namespace app-namespace
                      :transformations []}))})

(def config-map
  {:path ['-core '-v1 '-ConfigMap]
   :default-fn
   (fn [env] (let [{:keys  [app-name app-namespace]} (:options env)] 
     {:metadata {:namespace app-namespace
                 :name app-name}
      :data {}}))})

(def service
  {:path ['-core '-v1 '-Service]
   :default-fn
   (fn [env] (let [{:keys  [app-name app-namespace image-port]} (:options env)]
     {:metadata {:namespace app-namespace
                 :name app-name}
      :spec {:selector {:app app-name}
             :ports [{:name app-name :port 80 :targetPort image-port}]}}))})

(def deployment
  {:path ['-apps '-v1 '-Deployment]
   :default-fn
   (fn [env] (let [{:keys [app-name app-namespace image image-port]} (:options env)]
     {:metadata {:namespace app-namespace
                 :name app-name}
      :spec {:selector {:matchLabels {:app app-name}}
             :replicas 1
             :template {:metadata {:labels {:app app-name}}
                        :spec {:containers
                               [{:name app-name
                                 :image image
                                 :ports [{:containerPort image-port}]}]}}}}))})


(def nspace
  {:path ['-core '-v1 '-Namespace]
   :default-fn
   (fn [env] (let [{:keys [app-namespace]} (:options env)]
     {:metadata {:name app-namespace}}))})

(def secret
  {:path ['-core '-v1 '-Secret]
   :default-fn (fn [env] (let [{:keys [app-name app-namespace]} (:options env)]
                 {:metadata {:name (str app-name "-secrets")
                             :namespace app-namespace}}))})

(def storage-class
  {:path ['-core '-v1 '-StorageClass]
   :default-fn
   (fn [env] (let [{:keys [app-name]} (:options env)]
     {:metadata {:name app-name}}))})

(def config-file
  {:path ['-yaml '-v2 '-ConfigFile]
   :default-fn
   (fn [env] (let [{:keys [app-name file]} (:options env)]
     {:name app-name
      :properties {:file file}}))})


(def csi-driver
  {:path ['-storage '-v1 '-CSIDriver]
   :default-fn
   (fn [env] (let [{:keys [provisioner-name]} (:options env)]
     {:metadata {:name provisioner-name}
      :spec {:attachRequired false
             :podInfoOnMount true
             :volumeLifecycleModes ["Persistent"]}}))})