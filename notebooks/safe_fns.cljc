(ns safe-fns
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [sidebar :as sidebar]
   [nextjournal.clerk :as clerk]
   #?(:cljs ["@pulumi/pulumi" :as pulumi]))
  #?(:clj (:import [java.util Base64])))
^{::clerk/visibility {:code :hide :result :show}}
sidebar/sidebar



(defn b64e [s]
  #?(:cljs (.toString (js/Buffer.from s) "base64")
     :clj  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8"))))

(defn log [msg]
  #?(:cljs (js/console.log msg)
     :clj  (println msg)))

(defn make-paths [& path-groups]
  (mapcat (fn [{:keys [paths backend]}]
            (mapv (fn [p]
                    {:path p
                     :pathType "Prefix"
                     :backend {:service backend}})
                  paths))
          path-groups))

(def ^:public safe-fns
  {'str       str
   'b64e      b64e
   'println   log
   'make-paths make-paths
   'conj      conj

   ;; For JSON parsing in the future:
   ;; You cannot use js/JSON on the JVM. 
   ;; If you need this for simulation, you'll need a CLJ library (like data.json)
   ;; or a reader conditional here.
   })