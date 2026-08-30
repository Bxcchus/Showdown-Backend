package lol.pinkward.showdown.matchmaking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MatchmakingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchmakingServiceApplication.class, args);
    }
}
