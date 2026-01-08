(ns safe-fns
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [sidebar :as sidebar]
   [nextjournal.clerk :as clerk]
   #?(:cljs ["@pulumi/pulumi" :as pulumi]))
  #?(:clj (:import [java.util Base64])))



(defn b64e [s]
  #?(:cljs (.toString (js/Buffer.from (if (string? s) s (clj->js s))) "base64")
      :clj  (if (instance? pulumi.MockOutput s)
              s
              (.encodeToString (Base64/getEncoder) (.getBytes (str s) "UTF-8")))))

(defn log [msg]
  #?(:cljs (js/console.log msg)
     :clj  (println msg)))

(defn make-paths [& path-groups]
  (vec (mapcat (fn [{:keys [paths backend]}]
            (mapv (fn [p]
                    {:path p
                     :pathType "Prefix"
                     :backend {:service backend}})
                  paths))
          path-groups)))

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