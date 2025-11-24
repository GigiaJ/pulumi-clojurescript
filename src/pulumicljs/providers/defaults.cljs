(ns pulumicljs.providers.defaults
  (:require ["path" :as path]
            [configs :refer [cfg]]
            [pulumicljs.providers.k8s :as k8s]
            [pulumicljs.providers.harbor :as harbor]
            [pulumicljs.providers.docker :as docker]))


(def defaults
  {:k8s k8s/defaults
   :harbor harbor/defaults
   :docker docker/defaults})
