(ns providers.defaults
  (:require ["path" :as path]
            [configs :refer [cfg]]
            [providers.k8s :as k8s]
            [providers.harbor :as harbor]
            [providers.docker :as docker]))


(def defaults
  {:k8s k8s/defaults
   :harbor harbor/defaults
   :docker docker/defaults})
