(ns general
  (:require 
   [clojure.test :refer [deftest is run-tests]]
   [clojure.data :refer [diff]]
   [clojure.repl :refer [source-fn]]
   [nextjournal.clerk :as clerk]
   [provider-template :refer [config-map storage-class]]
   [pulumicljs.utils.general :refer [defregistry]]))

;; So a part of how the providers work is through what we call a registry.
;; The registry could be crafted manually, but it is clunky and verbose.
;; Instead we should automate the construction of the registry, so that new component definitions
;; get added automatically without any effort on our part.
;; Clojure provides an excellent way to do this in the form of macro generation.
;; With this we can generate *another* macro that is used to build the actual registry entry
;; which includes the method definition and constructor even.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'pulumicljs.utils.general/defregistry))

;; Utilizing the generator method we can now craft a registry macro
;; Now the registry entries themselves can't be entirely auto-generated
;; As the application has no way of knowing the true path to the constructor,
;; and you may wish to modify the default function even.
^{::clerk/auto-expand-results? true}
(defregistry provider-template-registry 
  {:provider-template {:provider-key :provider-template
                                 :resources
                                 {:config-map {:path [-here -lives -TheConstructor]
                                               :defaults-fn #(println %)}
                                  :storage-class {:path [-core -v1 -StorageClass]}}}})



;; Now CLJS has a hard time with type coercion correctly for integers, so when it is passed to Pulumi it will often fail.
;; Instead we can give it a way to just correctly coerce the type.
^{::clerk/visibility {:code :hide :result :show}}
(clerk/code (source-fn 'pulumicljs.utils.general/coerce-value))

