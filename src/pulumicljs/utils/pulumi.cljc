(ns pulumicljs.utils.pulumi
  (:require
   [clojure.string :as str]
   #?(:cljs ["@pulumi/pulumi" :as pulumi])))

(defrecord MockOutput [val])

(defn output [v] (->MockOutput v))

(defn output? [x]
  #?(:cljs (and (some? x) (.-apply x))
     :clj  (instance? MockOutput x)))

(defn all [outputs]
  #?(:cljs (pulumi/all (clj->js outputs))
     :clj  (output (map #(:val %) outputs))))


;; We can ensure safety in-case you accidentally apply on a non-output because it is
;; inconsequential, but it also allows you to treat BOTH identically and not worry
;; about the nuance of the asynchronous versus synchronous parts. The execution
;; order would be the same anyway in these situations.
(defn apply-output [out f]
  #?(:cljs
     (if (instance? pulumi/Output out)
       (.apply out (fn [val] (clj->js (f (js->clj val :keywordize-keys true)))))
       (f out))

     :clj
     (let [val (if (output? out) (:val out) out)]
       (f val))))

(defn new-resource [constructor-path resource-name resource-opts pulumi-opts]
  #?(:cljs (new constructor-path resource-name (clj->js resource-opts) (clj->js pulumi-opts))
     :clj {:constructor-path (str constructor-path)
           :name resource-name
           :props resource-opts
           :opts pulumi-opts}))