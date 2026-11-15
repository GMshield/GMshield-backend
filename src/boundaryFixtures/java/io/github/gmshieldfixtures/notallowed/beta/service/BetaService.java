package io.github.gmshieldfixtures.notallowed.beta.service;

import io.github.gmshieldfixtures.notallowed.gamma.service.GammaService;

public class BetaService {

    private final GammaService used = new GammaService();

    public GammaService used() {
        return used;
    }
}
