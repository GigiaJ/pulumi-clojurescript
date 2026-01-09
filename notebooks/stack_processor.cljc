^{::clerk/visibility {:code :hide :result :hide}}
(ns stack-processor
  (:require
   [clojure.test :refer [deftest is run-tests]]
   [clojure.data :refer [diff]]
   [clojure.repl :refer [source-fn]]
   [nextjournal.clerk :as clerk]
   [viewers :as v]
   [pulumicljs.utils.pulumi :as pulumi]
   [pulumicljs.processors.flesh :as proc]))

;; So the stack processor is a bit more nuanced, but it handles the post-*processing* of the resource config processor.
;; Much of the previous iteration will be lifted as conceptually for the most part it was fairly feature complete.
;; The largest change is in the fact that the secrets are no longer implicitly provided, but instead a user
;; is expected to instead refer to the output *of* their secret provider. In the future it might be practical to
;; add a way to mark the step that provides that and enable the resolution in the config interpreter to simply
;; assume that an un-found symbol is to be found within the secret retrieval/setting step.

