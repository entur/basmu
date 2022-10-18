package org.entur.basmu;

import org.apache.camel.CamelContext;
import org.apache.camel.FluentProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class BalhutTask implements CommandLineRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BalhutTask.class);

    private final CamelContext camelContext;

    public BalhutTask(CamelContext camelContext) {
        this.camelContext = camelContext;
    }

    @Override
    public void run(String... args) {
        FluentProducerTemplate fluentProducerTemplate = camelContext.createFluentProducerTemplate();
        fluentProducerTemplate.to("direct:makeCSV").request();
        LOGGER.debug("Sync eller async");
    }
}
