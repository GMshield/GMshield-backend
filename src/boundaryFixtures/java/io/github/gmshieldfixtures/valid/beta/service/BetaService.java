package io.github.gmshieldfixtures.valid.beta.service;

import io.github.gmshieldfixtures.valid.alpha.service.AlphaService;

public class BetaService {

    private final AlphaService used = new AlphaService();

    public AlphaService used() {
        return used;
    }
}
