package io.github.gmshieldfixtures.usesweb.beta.service;

import io.github.gmshieldfixtures.usesweb.alpha.web.AlphaEndpoint;

public class BetaService {

    private final AlphaEndpoint used = new AlphaEndpoint();

    public AlphaEndpoint used() {
        return used;
    }
}
