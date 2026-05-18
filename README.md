# Desktop Pets

Animated desktop pets (Ducky, Cat, Bird) written in Java Swing.

## Project layout

```
.
├── pom.xml
├── config.txt                # runtime config (read from working dir)
└── src/
    └── main/
        ├── java/
        │   └── com/desktoppets/
        │       ├── Main.java
        │       ├── Pet.java
        │       ├── Bird.java
        │       ├── Cat.java
        │       └── Ducky.java
        └── resources/
            └── Sprites/      # PNG sprite sheets (bundled into the jar)
```

Sprites are loaded from the classpath and are packaged inside the jar.
`config.txt` is read with a relative path from the working directory,
so it must remain at the directory from which the program is launched
(the project root by default).

## Build

```
mvn package
```

Produces `target/desktop-pets-1.0.0.jar`.

## Run

From the project root (so `Sprites/` and `config.txt` are found):

```
mvn exec:java
```

Or run the packaged jar:

```
java -jar target/desktop-pets-1.0.0.jar
```

## Configuration

Edit `config.txt`. Lines after the marker `Enter pets after this line:`
specify which pets to spawn. Supported values: `Ducky`, `Cat`, `Bird`.
