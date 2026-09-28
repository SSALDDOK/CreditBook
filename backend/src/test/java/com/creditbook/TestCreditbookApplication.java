package com.creditbook;

import org.springframework.boot.SpringApplication;

public class TestCreditbookApplication {

	public static void main(String[] args) {
		SpringApplication.from(CreditbookApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
