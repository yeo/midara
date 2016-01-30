(ns midara.builder.builder
    (:gen-class)
    (:use [clojure.java.shell :only [sh]])
    (require [tentacles.repos :as repos]
             [clojure.data.json :as json]
             [clojure.core.async
              :as a
              :refer [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout]]))

(defn read-meta
  ; Parse meta information
  [file]
  (json/read-str (slurp file) :key-fn keyword))

(defn write-meta
  ; write meta information
  [file content]
  (spit file (json/write-str content)))

(defn write-result
  ; write build result
  [owner repo commit r]
  (let [file (clojure.string/join "/" ["workspace" owner repo commit "build/midara-result.json"])]
    (spit file (json/write-str r))))

(defn read-result
  ; read build result
  [owner repo commit]
  (let [file (clojure.string/join "/" ["workspace" owner repo commit "build/midara-result.json"])]
    (json/read-str (slurp file) :key-fn keyword)))

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
  ; Execute build
  [args]
  (let [owner (get-in args [:repository :owner :name])
          name (get-in args [:repository :name])
          commit (get-in args [:head_commit :id])]
    (def pwd (System/getProperty "user.dir"))
    (def workspace (str pwd "/workspace"))
    (def workdir (clojure.string/join "/" [workspace owner name commit]))
    (def build-log (str workdir "/build/midara-build.log"))
    (def hook-manifest (str workdir "/build/hook-manifest.log"))
    (println (map #(.mkdir (java.io.File. %))
          [workspace (clojure.string/join "/" [workspace owner]) (clojure.string/join "/" [workspace owner name]) workdir (clojure.string/join "/" [workdir "src"]) (clojure.string/join "/" [workdir "build"])]))

    (write-meta hook-manifest args)

    (def cmd (str "(" "cd " workdir " && " "git clone --depth 1 git@github.com:" owner "/" name ".git src && pwd" ")"))
    (println "Build with command: " cmd " from base dir " (System/getProperty "user.dir"))
    (println (-> (java.io.File. ".") .getAbsolutePath))
    (sh "echo" cmd " > " build-log)
    ; The building process is as follow
    ; docker exec
    ; docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v workdir/src:/workspace run.sh
    ; while as run.sh looke like this:
    ;    source /workspace/.midara
    ; then `.main` function in `.midara` is kicked off and run
    (def docker (str "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v " workdir "/src:/workspace /run.sh"))
    (println "docker" "run" "--rm" "-v" "/var/run/docker.sock:/var/run/docker.sock" "-v" (str workdir "/src:/workspace") "source /workspace/.midara; main" " >> " build-log " 2>&1")
    ;(sh "docker" "run" "--rm" "-v" "/var/run/docker.sock:/var/run/docker.sock" "-v" (str workdir "/src:/workspace") "-v" "" "docker" "'source /workspace/.midara; main'" " >> " build-log " 2>&1")))
    (sh "/bin/sh" "-c" (str "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v " workdir "/src:/workspace docker -v " pwd "/resources/scripts/build:/build /build >> " build-log " 2>&1"))))

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
                )
                (write-result owner name commit build-result)
                ))
    )))))

(defn start
  ; Start the build process
  [headers body]
  (build headers body))
