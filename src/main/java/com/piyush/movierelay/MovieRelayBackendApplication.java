package com.piyush.movierelay;

import com.piyush.movierelay.telegram.TelegramProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TelegramProperties.class)
public class MovieRelayBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MovieRelayBackendApplication.class, args);
	}

}
