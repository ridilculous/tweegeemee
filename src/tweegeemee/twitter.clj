(ns tweegeemee.twitter
  (:require
   [twitter.api.restful :as tw]
   [twitter.oauth       :as tw-oauth]
   [twitter.request     :as tw-req]
   [clj-time.core       :as t]
   [clj-time.format     :as tf]
   [clj-time.periodic   :as tp]))

;; ======================================================================
(def DEBUG-NO-POSTING  false) ;; set true when you don't want to post

(def my-formatter (tf/formatter "YYMMdd"))

;; http://www.rkn.io/2014/02/13/clojure-cookbook-date-ranges/
(defn time-range
  "Return a lazy sequence of DateTime's from start to end, incremented
  by 'step' units of time."
  [start end step]
  (let [inf-range (tp/periodic-seq start step)
        below-end? (fn [x] (t/within? (t/interval start end)
                                     x))]
    (take-while below-end? inf-range)))

(defn day-of-week-today [] (t/day-of-week (t/today-at-midnight)))
(defn last-monday [] (-> (- (day-of-week-today) 1) t/days t/ago))
(defn last-sunday [] (-> (- (day-of-week-today) 0) t/days t/ago))
(defn weekago-monday [] (-> (+ 6 (day-of-week-today)) t/days t/ago))
(defn weekago-sunday [] (-> (+ 7 (day-of-week-today)) t/days t/ago))
(defn last-week
  "sequence of days from last Monday to Sunday"
  []
  (time-range (weekago-monday) (last-monday) (t/days 1)))

(defn make-oauth-creds
  [consumer-key consumer-secret access-token access-secret]
  (tw-oauth/make-oauth-creds
   consumer-key consumer-secret access-token access-secret))

(defn post-to-twitter
  "post status-text and the-image to twitter"
  [my-twitter-creds status-text the-image-filename]
  (if DEBUG-NO-POSTING
    (println "DEBUG: NOT POSTING TO TWITTER" status-text)
    (do
      (try
        (tw/statuses-update-with-media
         :oauth-creds @my-twitter-creds
         :body [(tw-req/file-body-part the-image-filename)
                (tw-req/status-body-part status-text)])
        (catch Exception e
          (println "caught twitter exception" (.getMessage e))))
      ;;(println "waiting for 5 seconds...")
      ;;(Thread/sleep 5000)
      )))

(defn- get-tgm-statuses
  ([my-twitter-creds my-screen-name N]
   (:body (tw/statuses-user-timeline
           :oauth-creds @my-twitter-creds
           :params {:count N
                    :screen-name @my-screen-name})))
  ([my-twitter-creds my-screen-name N max-id]
   (:body (tw/statuses-user-timeline
           :oauth-creds @my-twitter-creds
           :params {:count N
                    :screen-name @my-screen-name
                    :max-id max-id}))))

(defn- get-tgm-statuses-until-regex
  [my-twitter-creds my-screen-name regex]
  (loop [new-statuses (get-tgm-statuses my-twitter-creds my-screen-name 200)
         old-statuses '()]
    (let [statuses (concat old-statuses new-statuses)
          last-id (:id (last statuses))
          statuses-until-regex (take-while #(not (re-matches regex (:text %))) statuses)]
      ;;(println "n=" (count statuses) "nn=" (count statuses-until-regex) "id=" last-id)
      (if (< (count statuses-until-regex) (count statuses))
        statuses-until-regex
        (recur (get-tgm-statuses my-twitter-creds my-screen-name 200 last-id) statuses)))))

(defn get-todays-statuses
  "return a sequence of todays statuses."
  [my-twitter-creds my-screen-name]
  (let [today-str (.format (java.text.SimpleDateFormat. "yyMMdd") (System/currentTimeMillis))
        statuses (->> (get-tgm-statuses my-twitter-creds my-screen-name 100) ;; should be enough
                      (filter #(re-matches (re-pattern (str "^" today-str "_.*")) (:text %))))]
    statuses))

(defn get-last-weeks-statuses
  [my-twitter-creds my-screen-name]
  (let [regex-old (re-pattern (str "^" (tf/unparse my-formatter (weekago-sunday)) "_.*"))
        regex-new (re-pattern (str "^" (tf/unparse my-formatter (last-sunday)) "_.*"))
        statuses (get-tgm-statuses-until-regex my-twitter-creds my-screen-name regex-old)
        ;;_ (println "n1=" (count statuses))
        statuses (filter #(re-matches #"\d\d\d\d\d\d_\d\d\d\d\d\d_\w+.clj .*" (:text %)) statuses)
        statuses (drop-while #(not (re-matches regex-new (:text %))) statuses)
        ;;_ (println "n2=" (count statuses))
        ]
    statuses))

(defn get-a-months-statuses
  "ex. (get-a-months-statuses 16 1)"
  [my-twitter-creds my-screen-name year month]
  (let [prev-month (dec month)
        prev-month (if (= prev-month 0) 12 prev-month)
        prev-year  (if (= month 1) (dec year) year)
        regex-old (re-pattern (str "^" (format "%02d%02d" prev-year prev-month) ".*"))
        regex-new (re-pattern (str "^" (format "%02d%02d" year month) ".*"))
        statuses (get-tgm-statuses-until-regex my-twitter-creds my-screen-name regex-old)
        statuses (filter #(re-matches #"\d\d\d\d\d\d_\d\d\d\d\d\d_\w+.clj .*" (:text %)) statuses)
        statuses (drop-while #(not (re-matches regex-new (:text %))) statuses)]
    statuses))

(defn get-statuses
  [my-twitter-creds my-screen-name count]
  (let [statuses (:body (tw/statuses-user-timeline
                         :oauth-creds @my-twitter-creds
                         :params {:count count
                                  :screen-name @my-screen-name}))]
    statuses))

(defn post-status
  [my-twitter-creds status-str]
  (try (tw/statuses-update
        :oauth-creds @my-twitter-creds
        :params {:status status-str})
       (catch Exception e (println "Oh no! " (.getMessage e)))))
