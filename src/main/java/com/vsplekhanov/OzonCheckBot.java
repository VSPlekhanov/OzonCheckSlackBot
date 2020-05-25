package com.vsplekhanov;

import me.ramswaroop.jbot.core.common.Controller;
import me.ramswaroop.jbot.core.common.EventType;
import me.ramswaroop.jbot.core.common.JBot;
import me.ramswaroop.jbot.core.slack.Bot;
import me.ramswaroop.jbot.core.slack.models.Event;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@JBot
public class OzonCheckBot extends Bot {
    private static final String START = "старт";
    private static final String TEST_START = "test";
    private static final String STOP = "стоп";
    private static final String HEADLESS_OFF = "показать";
    private static final String HEADLESS_ON = "скрыть";
    private static final String PASS = "********";
    private static final String LOGIN = "*******@***.com";

    private static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static Logger log = LoggerFactory.getLogger(OzonCheckBot.class);

    private volatile boolean headless = true;
    private String lectureNum;
    private String startDate;
    private int count;
    private int day;
    private List<String> dates;
    private volatile boolean running;

    private void checkExit(WebSocketSession session, Event event) {
        if (STOP.equals(event.getText())) {
            reply(session, event, "Завершаю работу. Пока!");
            log.debug("Stopped by user");
            System.exit(0);
        }
    }


    @Controller(pattern = "\\?", events = EventType.DIRECT_MESSAGE)
    public void help(WebSocketSession session, Event event) {
        checkExit(session, event);
        String message = "Привет, я могу проверять доступность Ссылки для нажатия на сайте бонча"
                + "\nДоступные команды: "
                + "\n" + START + " - Начать новую проверку"
                + "\n" + STOP + " - Завершить работу программы"
                + "\n" + HEADLESS_OFF + " - Показать работу браузера (по умолчанию скрыта)"
                + "\n" + HEADLESS_ON + " - Скрыть работу браузера (по умолчанию скрыта)"
                + "\n? - Показать доступные команды";

        reply(session, event, message);
    }

    @Controller(pattern = HEADLESS_ON, events = EventType.DIRECT_MESSAGE)
    public void hide(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = true;
        log.debug("headless on");
        reply(session, event, "Скрытный режим активирован");
    }

    @Controller(pattern = HEADLESS_OFF, events = EventType.DIRECT_MESSAGE)
    public void show(WebSocketSession session, Event event) {
        checkExit(session, event);
        headless = false;
        log.debug("headless off");
        reply(session, event, "Скрытный режим выключен");
    }

    @Controller(events = EventType.DIRECT_MESSAGE)
    public void wrongMessage(WebSocketSession session, Event event) {
        checkExit(session, event);
        reply(session, event, "Что-то не то, может опечатка?");
    }

    @Controller(pattern = START, next = "getOrderId", events = EventType.DIRECT_MESSAGE)
    public void start(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (running) {
            log.debug("Trying to start second thread");
            reply(session, event, "Я не могу проверять 2 ссылки одновременно");
            stopConversation(event);
        } else {
            running = true;
            startConversation(event, "getOrderId");   // start conversation
            log.debug("Started by user");
            reply(session, event, "Привет, какой номер лекции нужно проверить? (как в расписании)");
        }
    }


    @Controller(next = "getStartDate", events = EventType.DIRECT_MESSAGE, pattern = "\\d")
    public void getOrderId(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d+$")) {
            lectureNum = event.getText().trim();
            nextConversation(event);
            reply(session, event, "Отлично, какой сегодня день месяца");
            log.debug("Get lecture num: " + lectureNum);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getOrderId");
        }
    }

    @Controller(events = EventType.DIRECT_MESSAGE, pattern = "\\d+")
    public void getStartDate(WebSocketSession session, Event event) {
        checkExit(session, event);
        if (event.getText().matches("\\d+$")) {
            day = Integer.parseInt(event.getText().trim());
            startCheckLoop(session, event);
        } else {
            reply(session, event, "Что-то не то, может опечатка?\nПопробуй ввести еще раз.");
            nextConversation("getStartDate");
        }
    }

    private void startCheckLoop(WebSocketSession session, Event event) {
        reply(session, event, "Окей, начинаю проверку.\nЧтобы завершить напиши \"стоп\"");
        log.debug("Start check");
        startCheck(session, event);

        reply(session, event, "Проверка закончена, чтобы начать новую напиши \"старт\"");
        stopConversation(event);
    }


    @Controller(pattern = STOP, next = "getOrderId", events = EventType.DIRECT_MESSAGE)
    public void stop(WebSocketSession session, Event event) {
        checkExit(session, event);
    }

    private void startCheck(WebSocketSession session, Event event) {

        WebDriver driver = null;
        try {
            if (headless) {
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--headless");
                driver = new ChromeDriver(options);
            } else {
                driver = new ChromeDriver();
            }
            log.debug("Driver is created");

            driver.get("https://lk.sut.ru/");
            WebElement userName = driver.findElement(By.name("users"));
            userName.sendKeys(LOGIN);
            WebElement password = driver.findElement(By.name("parole"));
            password.sendKeys(PASS);
            driver.findElement(By.name("logButton")).click();
            log.debug("Logged in");

            doCheck(session, event, driver);
        } finally {
            if (driver != null) {
                driver.close();
            }
        }
    }

    private void doCheck(WebSocketSession session, Event event, WebDriver driver) {
        clickOn(driver, By.className("title_item"));
        clickOn(driver, By.id("menu_li_807"));

        waitCoupleOfSec(2);

        List<WebElement> tableRows = driver.findElements(By.xpath("/html/body/div/div/table[2]/tbody/tr"));
        while (tableRows.isEmpty()) {
            waitCoupleOfSec(1);
            tableRows = driver.findElements(By.xpath("/html/body/div/div/table[2]/tbody/tr"));
        }

        int currDayId = -1;
        for (int i = 0; i < tableRows.size(); i++) {
            String text = tableRows.get(i).getText();
            if (text != null && text.matches("\\W+\\n\\d+.\\d+.\\d+") && text.split("\n").length > 1
                    && text.split("\n")[1].startsWith(String.valueOf(day))) {
                currDayId = i;
                break;
            }
        }

        if (currDayId < 0) {
            reply(session, event, "Не могу найти такого дня в расписании");
            running = false;
            return;
        }

        int currLectureId = -1;
        for (int i = currDayId; i < Math.min(currDayId + 6, tableRows.size()); i++) {
            String text = tableRows.get(i).getText();
            if (text.startsWith(String.valueOf(lectureNum))) {
                currLectureId = i;
                break;
            }
        }


        if (currLectureId < 0) {
            reply(session, event, "Не могу найти такой лекции в расписании");
            running = false;
            return;        }

        while (running) {
            try {
                driver.navigate().refresh();
                clickOn(driver, By.className("title_item"));
                clickOn(driver, By.id("menu_li_807"));

                tableRows = driver.findElements(By.xpath("/html/body/div/div/table[2]/tbody/tr"));
                while (tableRows.isEmpty()) {
                    waitCoupleOfSec(1);
                    tableRows = driver.findElements(By.xpath("/html/body/div/div/table[2]/tbody/tr"));
                }

                String text = tableRows.get(currLectureId).getText();
                if (text.contains("Ссылка")) {
                    reply(session, event, "Ссылка готова!");
                    running = false;
                    return;
                } else if (text.contains("Начать занятие")) {
                    reply(session, event, "Можно начать занятие!");
                } else {
                    log.warn("link is still not here...");
                }
            } catch (Exception e) {
                log.error(e.getMessage() + "  Exception... (maybe site is too slow)");
                e.printStackTrace();
            }
            waitCoupleOfSec(10);
        }
    }

    private void clickOn(WebDriver driver, By by) {
        List<WebElement> collapse = driver.findElements(by);
        while (collapse.isEmpty()) {
            waitCoupleOfSec(1);
            collapse = driver.findElements(by);
        }
        collapse.get(0).click();
    }

    public String getSlackToken() {
        return "xoxb-1122716004583-1140477999717-DN9DTXugCMAgYLXnFMtovs4x";
    }

    public Bot getSlackBot() {
        return this;
    }

    private void waitCoupleOfSec(int sec) {
        try {
            Thread.sleep(sec * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
