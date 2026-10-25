package zw.co.payport.gateway.ussd;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UssdMaskTest {

    @Test
    void pinsAreReplacedInTheKeptPath() {
        assertEquals("#*8*5*#*#", UssdService.mask("1357*8*5*1357*2468", Set.of(0, 3, 4)));
        assertEquals("1*2", UssdService.mask("1*2", Set.of()));
        assertEquals("", UssdService.mask("", Set.of(0)));
    }
}
