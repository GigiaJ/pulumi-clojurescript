(ns utils.providers.defaults
  (:require ["path" :as path]
            [configs :refer [cfg]]
            [utils.providers.k8s :as k8s]
            [utils.providers.harbor :as harbor]
            [utils.providers.docker :as docker]))


(def defaults
  {:k8s k8s/defaults
   :harbor harbor/defaults
   :docker docker/defaults})
