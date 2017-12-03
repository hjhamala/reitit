(ns reitit.ring.middleware
  (:require [meta-merge.core :refer [meta-merge]]
            [reitit.core :as r]))

(defprotocol IntoMiddleware
  (into-middleware [this data opts]))

(defrecord Middleware [name wrap])
(defrecord Endpoint [data handler middleware])

(defn create [{:keys [name wrap gen-wrap] :as m}]
  (when (and wrap gen-wrap)
    (throw
      (ex-info
        (str "Middleware can't both :wrap and :gen-wrap defined " m) m)))
  (map->Middleware m))

(extend-protocol IntoMiddleware

  #?(:clj  clojure.lang.APersistentVector
     :cljs cljs.core.PersistentVector)
  (into-middleware [[f & args] data opts]
    (if-let [{:keys [wrap] :as mw} (into-middleware f data opts)]
      (assoc mw :wrap #(apply wrap % args))))

  #?(:clj  clojure.lang.Fn
     :cljs function)
  (into-middleware [this _ _]
    (map->Middleware
      {:wrap this}))

  #?(:clj  clojure.lang.PersistentArrayMap
     :cljs cljs.core.PersistentArrayMap)
  (into-middleware [this data opts]
    (into-middleware (create this) data opts))

  #?(:clj  clojure.lang.PersistentHashMap
     :cljs cljs.core.PersistentHashMap)
  (into-middleware [this data opts]
    (into-middleware (create this) data opts))

  Middleware
  (into-middleware [{:keys [wrap gen-wrap] :as this} data opts]
    (if-not gen-wrap
      this
      (if-let [wrap (gen-wrap data opts)]
        (map->Middleware
          (-> this
              (dissoc :gen-wrap)
              (assoc :wrap wrap))))))

  nil
  (into-middleware [_ _ _]))

(defn- ensure-handler! [path data scope]
  (when-not (:handler data)
    (throw (ex-info
             (str "path \"" path "\" doesn't have a :handler defined"
                  (if scope (str " for " scope)))
             (merge {:path path, :data data}
                    (if scope {:scope scope}))))))

(defn expand [middleware data opts]
  (->> middleware
       (keep #(into-middleware % data opts))
       (into [])))

(defn compile-handler [middleware handler]
  ((apply comp identity (keep :wrap middleware)) handler))

(compile-handler
  [(map->Middleware
     {:wrap
      (fn [handler]
        (fn [request]
          (handler request)))})] identity)

(defn compile-result
  ([route opts]
   (compile-result route opts nil))
  ([[path {:keys [middleware handler] :as data}] opts scope]
   (ensure-handler! path data scope)
   (let [middleware (expand middleware data opts)]
     (map->Endpoint
       {:handler (compile-handler middleware handler)
        :middleware middleware
        :data data}))))

(defn router
  ([data]
   (router data nil))
  ([data opts]
   (let [opts (meta-merge {:compile compile-result} opts)]
     (r/router data opts))))

(defn middleware-handler [router]
  (with-meta
    (fn [path]
      (some->> path
               (r/match-by-path router)
               :result
               :handler))
    {::router router}))

(defn chain
  "Creates a vanilla ring middleware chain out of sequence of
  IntoMiddleware thingies."
  ([middleware handler data]
    (chain middleware handler data nil))
  ([middleware handler data opts]
   (compile-handler (expand middleware data opts) handler)))