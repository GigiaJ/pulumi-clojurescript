(ns provider-template)

(def provider-definition {:constructor ('.. 'provider-template '-Provider)
                          :name "provider-template"
                          :config {:kubeconfig 'kubeconfig}})

(defn config-map [{:keys  [app-name app-namespace]}]
  {:metadata {:namespace app-namespace
              :name app-name}
   :data {}})

(defn service [{:keys  [app-name app-namespace image-port]}]
  {:metadata {:namespace app-namespace
              :name app-name}
   :spec {:selector {:app app-name}
          :ports [{:name app-name :port 80 :targetPort image-port}]}})

(defn storage-class [{:keys [app-name]}]
  {:metadata {:name app-name}})