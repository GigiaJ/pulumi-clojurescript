(ns pulumicljs.utils.safe-fns
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [pulumicljs.utils.pulumi :as pulumi])
  #?(:clj (:import [java.util Base64]
                   [pulumicljs.utils.pulumi MockOutput])))

(defn b64e [s]
  #?(:cljs (.toString (js/Buffer.from (if (string? s) s (clj->js s))) "base64")
     :clj  (if (instance? MockOutput s)
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
   'conj      conj})