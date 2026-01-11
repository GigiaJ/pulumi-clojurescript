(ns pulumicljs.providers.defaults
  #?(:clj  (:require [pulumicljs.providers.loader :refer [build-complete-library]]))
  #?(:cljs (:require-macros [pulumicljs.providers.loader :refer [build-complete-library]])))

(def library (build-complete-library))