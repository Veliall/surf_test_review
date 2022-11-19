import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;


public class Index {
    //в java приняты private, в крайнем случае protected объявления полей, инкапсуляция)
    // желательно final, мы явно не планируем подменять поля в подобном классе
    // НЕПОТОКОБЕЗОПАСНАЯ структура используется в контексте взаимодействия из потоков...
    // плохая ситуация, даже если "безопасно" в моменте
    // не видно необходимости объявлять по классу реализации,
    // лучше по интерфейсу Map, всё использованное API в нём есть
    // название подразумевает обратный индекс,
    // но по использованному конструктору без параметров ясно,
    // что дерево будет сортироваться в стандартном для строк порядке
    TreeMap<String, List<Pointer>> invertedIndex;

    ExecutorService pool;

    public Index(ExecutorService pool) {
        this.pool = pool;
        //почему в конструкторе? Очень неявно
        invertedIndex = new TreeMap<>();
    }

    // InPath при передачи Path как String выглядит не очень, скорее всего ничего не мешает методу принимать уже готовый Path
    public void indexAllTxtInPath(String pathToDir) throws IOException {
        //неплохо бы сделать проверку параметра пришедшего в метод

        //неговорящее имя переменной
        Path of = Path.of(pathToDir);

        //непонятно почему выбран начальный размер 2, да ещё и как "магическое число"
        BlockingQueue<Path> files = new ArrayBlockingQueue<>(2);


        try (Stream<Path> stream = Files.list(of)) {
            //Вообще плохо - использование блокирующей очереди, при этом добавлять через add, который никак не реализует
            // возможности данного вида очередей, если необходимо чтобы поток ждал пока в очереди освободится место есть put()
            // а тут 100% полетит ошибка при переполнении
            stream.forEach(files::add);
        } // я бы обрабатывал ошибку (а скорее ошибки) тут, оборачивая в кастомные ошибки приложения с описанием проблемы.
        //а не прокидывал выше, хотя если это библа, а не сервисный класс, то можно и пробросить

        // ну и как-то некрасиво так в лоб три раза копипастить) DRY!
        // лучше вынести количество потоков либо в константу и в цикле запустить по константе,
        // или вынести в параметры метода, отдав на откуп коду, который использует
        // (лучше, так как с той стороны виднее сколько потоков в пуле)
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
        pool.submit(new IndexTask(files));
    }

    // если поменяем объявление поля по интерфейсу - тут тоже изменить
    public TreeMap<String, List<Pointer>> getInvertedIndex() {
        return invertedIndex;
    }

    //Это не C# и не Go) пишем методы с маленькой буквы)
    public List<Pointer> GetRelevantDocuments(String term) {
        // потенциальный NPE
        // а также вернёт null если нет ничего по входящему ключу,
        // это не очень хорошо, лучше в таком случае вернуть пустую коллекцию
        return invertedIndex.get(term);
    }

    // Возможно есть смысл подумать о замене List<Point> на упорядченную, отсортированную структуру.
    // Что нибудь из наследников SortedSet<Point>, хотя это даст накладные расходы на операции вставки и т.д.
    // и возможно нам действительно лучше потратится здесь на поиск
    public Optional<Pointer> getMostRelevantDocument(String term) {
        return invertedIndex.get(term).stream().max(Comparator.comparing(o -> o.count));
    }


    //Смысл сделать класс вложенным, если он не используется сам по себе - есть,
    // а вот смысл делать его статичным - ускользает
    // хотел предложить закрыть приватным уровнем доступа, но это сломает возможность получения данных из методов
    // основного класса, где коллекции типизированы данным классом
    static class Pointer {

        // желательно final поля
        // имеет смысл использовать примитивный тип int
        private Integer count;
        private String filePath;

        public Pointer(Integer count, String filePath) {
            this.count = count;
            this.filePath = filePath;
        }

        @Override
        public String toString() {
            return "{" + "count=" + count + ", filePath='" + filePath + '\'' + '}';
        }
    }

    class IndexTask implements Runnable {

        private final BlockingQueue<Path> queue;

        public IndexTask(BlockingQueue<Path> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                //Не нравится подход в подборе имен переменных, хотелось бы что-то более говорящее
                Path take = queue.take();
                List<String> strings = Files.readAllLines(take);

                //даже не смог воспринимать в одну строчку, пришлось перенести по методам
                strings.stream()
                        .flatMap(str -> Stream.of(str.split(" ")))
                        .forEach(word -> invertedIndex.compute(word, (k, v) -> {
                            // немодифицируемая версия листа вернётся
                            // и следует подумать о том, что этот код будет выполнятся в многопоточной среде
                            // и модифицировать данные будут разные потоки,
                            // race condition, если использовать обычные коллекции
                            // вынести бы этот кусок в отдельный метод, чтобы легче тестировать и читать,
                            // и можно будет заменить compute на merge с вызовом метода
                    if (v == null) return List.of(new Pointer(1, take.toString()));
                    // есть return после if, else лучше опустить для лучшей читабельности
                    else {
                        ArrayList<Pointer> pointers = new ArrayList<>();

                        // два раза цикл гоняем, да ещё и с ошибкой подсчёта, если документ добавляется впервые
                        // (сразу инициализируем с 1 и ему тоже прибавляем в след. цикле)
                        if (v.stream().noneMatch(pointer -> pointer.filePath.equals(take.toString()))) {
                            pointers.add(new Pointer(1, take.toString()));
                        }

                        v.forEach(pointer -> {
                            if (pointer.filePath.equals(take.toString())) {
                                pointer.count = pointer.count + 1;
                                // камон, инкремент)) не ошибка конечно, ну как-то)))
                            }
                        });

                        pointers.addAll(v);

                        return pointers;
                    }

                }));

            } catch (InterruptedException | IOException e) {
                throw new RuntimeException();
            }
        }
    }
}