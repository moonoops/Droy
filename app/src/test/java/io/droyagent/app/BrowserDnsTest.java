package io.droyagent.app;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class BrowserDnsTest {
    private String response(String question,String addresses) {
        return "{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\""+question+"\",\"type\":1}],\"Answer\":["+addresses+"]}";
    }
    @Test public void parsesOnlyAddressesForTheExpectedQuestion() throws Exception {
        assertEquals("93.184.216.34",BrowserDns.parse("example.com",1,response("example.com.","{\"type\":1,\"data\":\"93.184.216.34\"}")).get(0).getHostAddress());
        assertThrows(IOException.class,()->BrowserDns.parse("example.com",1,response("other.example", "")));
        assertThrows(IOException.class,()->BrowserDns.parse("example.com",1,"{\"Status\":2}"));
    }
    @Test public void privateAndMixedDnsAnswersRemainRejected() {
        for(String address:new String[]{"127.0.0.1","192.168.1.1","198.18.0.1","169.254.169.254"})
            assertThrows(IOException.class,()->BrowserDns.parse("example.com",1,response("example.com", "{\"type\":1,\"data\":\"93.184.216.34\"},{\"type\":1,\"data\":\""+address+"\"}")));
    }
    @Test public void answersCannotTriggerAnotherHostnameResolution() {
        assertThrows(IOException.class,()->BrowserDns.parse("example.com",1,response("example.com", "{\"type\":1,\"data\":\"localhost\"}")));
    }
}
