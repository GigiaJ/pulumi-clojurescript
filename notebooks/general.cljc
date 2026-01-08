(ns general
  (:require 
   [nextjournal.clerk :as clerk]))


^{::clerk/visibility {:code :hide :result :hide}}
(defn coerce-value [v]
  (if (string? v)
    (or (parse-long v) v)
    v))