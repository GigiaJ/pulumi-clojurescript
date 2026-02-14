(ns pulumicljs.processors.stack-processor
  (:require
   [pulumicljs.processors.config-processor :as proc]
   [pulumicljs.utils.general :refer [deep-merge p-> coerce-value p-threading p-lift]]
   [pulumicljs.utils.safe-fns :refer [safe-fns]]
  [clojure.walk :as walk]
    [pulumicljs.utils.pulumi :as pulumi]
  [clojure.string :as str]
   )
)

(defn generic-transform
  "Takes a creator function and executes it with resolved arguments,
   handling asynchronicity when secrets are present."
  [creator-fn resource-opts base-values options]
  (pulumi/apply-output options
                       #(creator-fn (deep-merge base-values (proc/resolve-template resource-opts %)))))

