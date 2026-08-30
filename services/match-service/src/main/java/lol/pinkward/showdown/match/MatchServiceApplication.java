package lol.pinkward.showdown.match;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchServiceApplication.class, args);
    }
}
