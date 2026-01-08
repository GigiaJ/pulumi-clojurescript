(ns pulumi
  #?(:cljs (:require ["@pulumi/pulumi" :as pulumi])))

(defrecord MockOutput [val])

(defn output [v] (->MockOutput v))

(defn output? [x]
  #?(:cljs (and (some? x) (.-apply x))
     :clj  (instance? MockOutput x)))

(defn all [outputs]
  #?(:cljs (pulumi/all (clj->js outputs))
     :clj  (output (map #(:val %) outputs))))

(defn apply-output [out f]
  #?(:cljs (.apply out #(f (js->clj %)))
     :clj  (let [val (if (output? out) (:val out) out)]
             (output (f val)))))