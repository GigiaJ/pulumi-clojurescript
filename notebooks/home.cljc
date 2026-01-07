{:nextjournal.clerk/visibility {:code :hide :result :show}}
(ns home
  (:require
   [clojure.java.io :as io]
   ;;[sidebar :as sidebar]
   [nextjournal.clerk :as clerk]))

(clerk/html [:style "header { display: none !important; }"])

;; ## Pulumi-CLJS library notebook
;; I'll be outlining here my thoughts alongside building functional code to neatly interface with Pulumi
;; I've opted to also experiment with using Clerk for my literate programming needs, so the actual layout of
;; the notebook may end up being messy.
;; A solid upside is that on each of these pages, the code that renders and my notes and comments are visible alongside it.
;;
;; The major goal is to allow the configs to be as malluable as needed. Meaning anything you could do in the base JS version should function here and
;; be parsed and handled appropriately. Instead of entirely leaning on the automation API, this should both augment normal usage as well as full on
;; multi-stage and multi-stack deployments that do want to rely on the automation API.
;;
;; Initially this was built with the single goal of getting a functional build working and after finishing that it became apparent that configs
;; were restrictive and prevented ideal use like declaring multiple resources in a single 'definition'. Definition and resources being something defined below.
;; A secondary goal for this revision is to abstract out any CLJS away from core CLJ code for both re-use as well as to aid in actually visualizing it
;; here in Clerk.
;;
;;
;; ### Outline of Behavior
;; To define some terminology fairly exclusive to this idiomatic use of Clojure(script) for Pulumi resource definitions is necessary. Since we go beyond simply defining
;; resources alone and we also encapsulate group-able ideas into consistent logical groupings. The general logical groupings being Config Definitions. These define a collection
;; of resources needed to get to a functional state. They can rely on other elements already being deployed or existing in another stack, but generally speaking they are a       set of changes or deployments to get, as an example, an application from not deployed nor configured to configured in say the vault or other secret manager, DNS entries made,
;; Helm chart configured, K8s secret deployed, K8s Gateway and HTTPRoute configured, and the Helm chart deploying the service, deployment, and whatever else.
;;
;; 
;; As such these encapsulate a single goal in which we have a defined end state for the application as well as a start state. At a higher level we offer the ability to define
;; the provider needs for these Config Definitions (effectively the infrastructure dependencies). We define this point as the Stack Processor. The Stack Processor handles
;; setting up the state for the providers to be available that the Config Definitions will use and as such some level of Stack Processor Definitions *do* exist, but generally are
;; defined in the Executable Core Processor. To add, the code that handles the Config Definitions is the Config Processor. It does as the name implies. Each of these will be linked and
;; templates/examples provided alongside helpful diagram.
;; Alongside the processors and definitions we have Composable Provider Templates, Resource Definition Templates, and a generic set of utility functions for the library.

(clerk/image (io/file "resources/images/library-outline.png"))
