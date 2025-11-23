(ns utils.defaults
  (:require ["path" :as path]
            [configs :refer [cfg]]
            [utils.k8s :as k8s]
            [utils.harbor :as harbor]
            [utils.docker :as docker]))


(def defaults
  {:k8s k8s/defaults
   :harbor harbor/defaults
   :docker docker/defaults})
