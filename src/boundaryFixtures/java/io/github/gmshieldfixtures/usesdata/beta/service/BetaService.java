package io.github.gmshieldfixtures.usesdata.beta.service;

import io.github.gmshieldfixtures.usesdata.alpha.data.AlphaRow;

public class BetaService {

    private final AlphaRow used = new AlphaRow();

    public AlphaRow used() {
        return used;
    }
}
