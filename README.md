# Currently very much in alpha

Initially this was a part of my Pulumi configs, but as the scope and usability expanded it made more sense for it to be a library instead.
It's fully functional for what it seeks to do, which is provide an idiomatic way of expressing infrastructure as code through a familiar LISP style.

The goal isn't to limit functionality, but instead expand behavior and usage patterns by providing the full power functionality of Clojure through composability and metaprogramming.

The best example of usage are [my own infrastructure configs](https://github.com/GigiaJ/iac-cljs-configs#)


I'll detail more later (check my config README if you're desperate to get this alpha build running).














Future additions needed:
- Extra CRDs aren't installed by default and currently have no mechanism to install them
- There *must* be a way to declare duplicate resources in the same config group. Otherwise we create massive bloat without purpose. Config file resources or certificates being individually declarable several times is NOT a bad thing. This would provide greater fluidity. We still need to refine the multi-resource deployment, but certificates is a good start with the direction that should take.
- Should also revise default-fn to recursively call certificate and just allow the default-fn to unwind the values.
- Component spec really needs to be moved out of stack_processor as it is just such a large block of data that so better belongs w/ the providers themselves.
- It may be helpful to redesign the stack mechanism entirely so that resources and such are declared like:
    ```
    (def config
        {:stack [
            {:item-name 
                {:options-in-here}} 
            {:item-name-2 
                {:options-in-here}}
        ]})
    ```
    Where this provides much clearer association and each resource has its options readily available. As such you could declare duplicate keys in the same config. It would make resource associations much more explicit and cleaner written.
    It would require a decent amount of revision, so no rush on this.
- Currently, certificates relies upon a prior step existing and that in itself is a bit of an anti-pattern... So in the future our options NEED some way of informing the resolver and deployer that it has custom execution.
    ```
    :k8s:certificates
    {:constructor (.. cert-manager -v1 -Certificate)
        :provider-key :k8s
        :defaults-fn (fn [env]
                    (p-> env :options :vault:prepare "stringData" .-domains
                            #(vec
                            (for [domain (js/JSON.parse %)] 
                                (let [clean-name (clojure.string/replace domain #"\." "-")]
                                {:_suffix clean-name
                                :spec {:dnsNames [domain (str "*." domain)]
                                        :secretName (str clean-name "-tls")}})))))}
    ```
    The above is unideal. I think the best path forward for that is an override?  Considering that some might not use Vault.
    It might, instead, benefit from a high level user declaration of intent regarding the location of their secrets/settings. I mentioned above to have it resolve based on what providers utilized (within reason for support). That removes the inherent reliance, but it still does leave resolution in the default-fn in an unideal manner. It doesn't work to make top-level functions resolve on the outer layer as the Vault entry wouldn't exist yet. 
    If we do the user intent, we can at least change it to be a standard such as 
    ```
    (p-> env :options :secrets .-domains #(function here))
    ```
    I should add that this function would be in a more *plugin* since it isn't inherently a built-in for K8s. Same for Gateway.
    It wouldn't hurt to add some extension for developing these too. Increasing clarity on manner of declaration can not hurt.
- Default values (like in K8) are opinionated. They do need to outline how to use a structure for example, but it should also be convenient to use any other resource like Nginx instead of Traefik or Azure instead of Cloudflare. A macro could be applied to them (preferably after their declaration, so their default state remains opinionated) to swap out which provider an individual chooses to use. It can be an added field in the core declarations for processing. Obvious goal for this is expansiveness. There should be clean, reusable defaults and everything should be easily modifiable and expandable.

- Resource declarations might benefit from being *able* to splinter when needed. Currently they are VERY MUCH locked to a singleton pattern. While we can "loop" over stuff inside a declaration it still only ever makes *one* resource.
- pulumi2crd should perhaps include some install instructions and some insight into usage
Currently the script builds correctly, but since they are version dependant we might want to have some sort of version management for each of these components. That way they can be updated in a similar mechanism to a normal npm package. We can make CICD pipelines for them for this with some sort of cron scheduling. Emulating Renovate behavior (or we can see if Renovate can be useful here even). The script hosting repo only generates two CRDs, but it does outline the behavior well at least. Though locked into using an NPM package deployment isn't ideal. Those generated CRDs should not be baked into the provider utils but instead be treated as an expansion. This way it is neatly organized into official and extended functionality.
- Local file/config loading or something should also be a provider, as obviously we would want to be able to pass through virtually anything to a service. That way they can be accessed later (this would replace the weird load-yaml that is a leftover from prior iterations)
- Should add a Cloudflare provider at some point
- I should also perhaps make loading in providers more automatic. Our defaulting is quite opinionated. It isn't inherently "bad" as it makes generation far simpler, but perhaps we have a base and a "read and expanded" variant available to the downstream.
- Currently unable to effectively destructure secrets in the execute function in the current design. However, since we'd want to change to remove the anti-pattern mentioned above, we'd ideally actually just reference secrets through the vault resource output from the given resource config's stack execution.