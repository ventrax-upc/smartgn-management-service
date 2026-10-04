package com.smartgn.management.adapter.out.persistence;
import com.smartgn.management.shared.application.port.out.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Component
public class SpringTransactionRunner implements TransactionRunner {
    private final TransactionTemplate template;
    public SpringTransactionRunner(PlatformTransactionManager manager) { template = new TransactionTemplate(manager); }
    public <T> T run(Supplier<T> work) { return template.execute(status -> work.get()); }
}
