(ns heckendorf.web
  (:gen-class)
  (:require [org.httpkit.server :as http-kit]
            [taoensso.sente.server-adapters.http-kit :refer (get-sch-adapter)]
            [hiccup.core :as hiccup]
            [hiccup.element :refer [javascript-tag]]
            [ring.middleware.defaults]
            [ring.middleware.anti-forgery :as anti-forgery]
            [compojure.core :as comp :refer (defroutes GET POST)]
            [compojure.route :as route]
            [clojure.core.async :as async :refer (<! <!! >! >!! put! chan go go-loop)]
            [taoensso.timbre :as timbre :refer (tracef debugf infof warnf errorf)]
            [taoensso.sente :as sente]
            [heckendorf.game :refer [get-game update-game new-game save-games!]]
            [heckendorf.leaderboard :refer [update-leaderboard! get-leaderboard!]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as s])
  (:import [java.util.concurrent Executors TimeUnit]))

(let [packer :edn
      chsk-server (sente/make-channel-socket-server!
                    (get-sch-adapter) {:packer packer
                                       :user-id-fn (fn [ring-req]
                                                     (:client-id ring-req))})
      {:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]
  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))

;; Use fingerprinted (cache-busting) app.js when created for production builds.
(let [js-str "resources/public/js/compiled/app.js"
      subm (some->> "public/js/compiled/manifest.edn"
                    io/resource
                    slurp
                    edn/read-string)]
  (def js-path (-> (cond-> js-str (map? subm) subm)
                   (s/replace #"resources/public/" ""))))

(defn landing-pg-handler [ring-req]
  (hiccup/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]]
     [:body
      [:div#sente-csrf-token
       {:data-csrf-token (force anti-forgery/*anti-forgery-token*)}]
      [:div#app]
      [:script {:src js-path}]
      (javascript-tag "heckendorf.core.init();")]]))

(defroutes ring-routes
  (GET "/" ring-req (landing-pg-handler ring-req))
  (GET "/chsk" ring-req (ring-ajax-get-or-ws-handshake ring-req))
  (POST "/chsk" ring-req (ring-ajax-post ring-req))
  (route/resources "/")
  (route/not-found "<h1>Page not found</h1>"))

(def main-ring-handler
  (ring.middleware.defaults/wrap-defaults
    ring-routes
    ;; Workaround for client not receiving CSRF token in HTML on page load.
    (dissoc ring.middleware.defaults/site-defaults :static)))

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s"
  :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defmethod -event-msg-handler :chsk/ws-ping [_] nil)

(defmethod -event-msg-handler :chsk/uidport-open [_] nil)

(defmethod -event-msg-handler :chsk/uidport-close [_] nil)

(defmethod -event-msg-handler :game/start
  [{:keys [?reply-fn client-id]}]
  (?reply-fn (get-game client-id)))

(defmethod -event-msg-handler :game/new
  [{:keys [?reply-fn client-id]}]
  (?reply-fn (new-game client-id)))

(defmethod -event-msg-handler :game/action
  [{:keys [?data ?reply-fn client-id]}]
  (?reply-fn (update-game client-id 0 ?data)))

(defmethod -event-msg-handler :game/submit
  [{:keys [?data ?reply-fn client-id]}]
  (update-leaderboard! client-id ?data ?reply-fn))

(defmethod -event-msg-handler :game/leaderboard
  [{:keys [?data ?reply-fn client-id]}]
  (?reply-fn (get-leaderboard!)))

(defonce scheduler_ (atom nil))
(defn stop-scheduler! [] (when-let [stop-fn @scheduler_] (stop-fn)))
(defn start-scheduler! []
  (stop-scheduler!)
  (reset! scheduler_
          (let [scheduled-future (.scheduleAtFixedRate
                                   (Executors/newScheduledThreadPool 1)
                                   save-games! 0 300 TimeUnit/SECONDS)]
            #(.cancel scheduled-future false))))

(defonce router_ (atom nil))
(defn stop-router! [] (when-let [stop-fn @router_] (stop-fn)))
(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-server-chsk-router!
            ch-chsk event-msg-handler)))

(defonce web-server_ (atom nil))
(defn stop-web-server! [] (when-let [stop-fn @web-server_] (stop-fn)))
(defn start-web-server! [dev?]
  (stop-web-server!)
  (let [port (if dev? 9090 80)
        ring-handler (var main-ring-handler)
        [port stop-fn]
        (let [stop-fn (http-kit/run-server ring-handler {:port port})]
          [(:local-port (meta stop-fn)) (fn [] (stop-fn :timeout 100))])
        uri (format "http://localhost:%s/" port)]
    (infof "Web server is running at `%s`" uri)
    (reset! web-server_ stop-fn)))

(defn in-dev? [args]
  (not= args '("prod")))

(defn stop! [] (stop-scheduler!) (save-games!) (stop-router!) (stop-web-server!))
(defn start! [args]
  (start-router!)
  (start-web-server! (in-dev? args))
  (start-scheduler!)
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'stop!))
  nil)

(defn -main "For `lein run`, etc." [& args] (start! args))
