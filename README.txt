## Author
**Anas**

---

## 📋 Configuration Requirements

To get started, ensure you have the following installed and configured:

1. **RabbitMQ**: A robust messaging broker for handling message queues.
2. **Cassandra**: A NoSQL database optimized for high read and write throughput.
   - Note: The default port setting in the code is **9999**. Please change this as needed based on your configuration.

---

## 🚀 Running the Application

Follow these steps to run the application:

1. **Start RabbitMQ**: Make sure your RabbitMQ service is running.
2. **Run the Consumer**: Execute the consumer code to start receiving messages.
3. **Run the Producer**: Launch the producer code to send messages.

---

## 📊 Viewing Real-Time Charts

To visualize the data in real-time, follow these steps:

1. **Open the Chart Folder**: Navigate to the chart folder in your preferred code editor (Visual Studio Code is recommended).
2. **Open Terminal**: In the terminal, type the following command to start the server:
   ```bash
   node server.js