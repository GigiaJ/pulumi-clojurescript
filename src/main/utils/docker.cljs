(ns utils.docker
  (:require
   [utils.general :refer [generic-transform deep-merge new-resource component-factory resource-factory deploy-stack-factory iterate-stack]]
   ["@pulumi/docker-build" :as docker]
   ["path" :as path]
   [configs :refer [cfg]]))

(defn image [env]
  (let [{:keys [app-name docker:image-opts]} env
        context-path (.. path (join "." (-> cfg :resource-path)))
        dockerfile-path (.. path (join context-path (str app-name ".dockerfile")))
        base-args (if (:is-local docker:image-opts)
                    {:context {:location context-path}
                     :dockerfile {:location dockerfile-path}
                     :imageName (str (-> cfg :docker-repo) "/" app-name ":latest")}
                    {})]
    base-args))

(def defaults
  {:image image})

(def component-specs-defs
  {:root-sym 'docker
   :provider-key :harbor
   :resources {:image {:path ['-Image]}}})