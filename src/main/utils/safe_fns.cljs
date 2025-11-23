(ns utils.safe-fns)

(defn make-paths [& path-groups]
  (mapcat (fn [{:keys [paths backend]}]
            (mapv (fn [p]
                    {:path p
                     :pathType "Prefix"
                     :backend {:service backend}})
                  paths))
          path-groups))


(defn make-listeners [domains-or-json]
  (let [domains (if (string? domains-or-json)
                  (js->clj (js/JSON.parse domains-or-json))
                  domains-or-json)]
    (vec
     (mapcat
      (fn [domain]
        (let [clean-name (clojure.string/replace domain #"\." "-")
              secret-name (str clean-name "-tls")]

          [{:name (str "https-root-" clean-name)
            :port 8443
            :protocol "HTTPS"
            :hostname domain
            :tls {:mode "Terminate"
                  :certificateRefs [{:name secret-name}]}
            :allowedRoutes {:namespaces {:from "All"}}
            }

           {:name (str "https-wild-" clean-name)
            :port 8443
            :protocol "HTTPS"
            :hostname (str "*." domain)
            :tls {:mode "Terminate"
                  :certificateRefs [{:name secret-name}]}
            :allowedRoutes {:namespaces {:from "All"}}
            }]))
      domains))))

(def ^:public safe-fns
  {'str str
   'b64e (fn [s] (-> (.from js/Buffer s) (.toString "base64")))
   'println #(js/console.log %)
   'make-paths make-paths
   'make-listeners make-listeners
   'parse #(js->clj (js/JSON.parse %))})