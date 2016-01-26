(ns midara.builder.builder
    (:gen-class)
    (:use [clojure.java.shell :only [sh]])
    (require [tentacles.repos :as repos]
             [clojure.core.async
              :as a
              :refer [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout]]))

(defn -description
  ; Get description for build
  [state]
  (if (= state "pending")
    "Midara triggered build"
    (if (= state "sucess")
      "Midara build succeed"
      (str "Midara build: " state))
  )
)

(defn set-status
  ; Set status of commit
  [owner repo-name commit state]
  (let [b {:state state :target_url (str "https://05f2abec.ngrok.io/build/" owner "/" repo-name "/" commit) :description (-description state) :context "continuous-integration/midara" :auth (System/getenv "GITHUB_AUTH_TOKEN")}]
    (repos/create-status owner repo-name commit b)
  ))

(defn -execute
  ; Execute build script.
  [repo]
  (println repo)
  )

(defn -execute
  ; Execute build
  [args]
  (let [owner (get-in args [:repository :owner :name])
          name (get-in args [:repository :name])
          commit (get-in args [:head_commit :id])]
    (def pwd (System/getProperty "user.dir"))
    (def workspace (str pwd "/workspace"))
    (def workdir (clojure.string/join "/" [workspace owner name commit]))
    (def build-log (str workdir "/build/midara-build.log"))
    (println (map #(.mkdir (java.io.File. %))
          [workspace (clojure.string/join "/" [workspace owner]) (clojure.string/join "/" [workspace owner name]) workdir (clojure.string/join "/" [workdir "src"]) (clojure.string/join "/" [workdir "build"])]))

    (println "Build with command: echo " args  build-log "; sleep 15"))
    (println (System/getProperty "user.dir"))
    (println (-> (java.io.File. ".") .getAbsolutePath))
    (sh "sh" "-c" (str "echo " args "> " build-log "; sleep 15")))

(defn build
  ; Start the build process
  [headers {:keys [repo commit] :as args}]
  (if (= "push" (get headers "x-github-event"))
    (let [owner (get-in args [:repository :owner :name])
          name (get-in args [:repository :name])
          commit (get-in args [:head_commit :id])]
      (let [result (set-status owner name commit "pending")]
        (println (str "creating " result))
        (if (= 200 (result :status))
          (println "Fail to create status. Abandon build")
          (go (let [build-result (-execute args)]
            (if (= 0 (build-result :exit))
              (do
                (println "Build succesful")
                (let [status-result (set-status owner name commit "success")]
                  (println (str "creating result: " status-result))))
              (println "Build fail")
            )))
    )))))

(defn start
  ; Start the build process
  [headers body]
  (build headers body))
