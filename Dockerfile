FROM openjdk:17-slim

# Устанавливаем ffmpeg, wget, python3, и python3-pip
RUN apt-get update && apt-get install -y ffmpeg wget python3 python3-pip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Устанавливаем yt-dlp через pip
RUN pip install --upgrade yt-dlp

# Установите рабочую директорию
WORKDIR /app

# Создание директории для данных внутри рабочей директории
RUN mkdir data

# Копируем собранный .jar файл в контейнер
COPY target/StreamToArticleConverter-0.0.1-SNAPSHOT.jar app.jar


# Открываем порт 8080 для доступа к приложению
EXPOSE 8080

# Запускаем приложение
CMD ["java", "-jar", "/app/app.jar"]