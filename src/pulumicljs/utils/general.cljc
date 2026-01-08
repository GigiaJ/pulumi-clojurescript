(ns pulumicljs.utils.general)

(defn coerce-value [v]
  (if (string? v)
    (or (parse-long v) v)
    v))