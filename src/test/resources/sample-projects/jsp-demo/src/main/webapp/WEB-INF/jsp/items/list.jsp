<%@ page contentType="text/html; charset=UTF-8" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ include file="../common/header.jspf" %>
<html>
<body>
<c:forEach var="item" items="${items}">
  <a href="<c:url value='/items/${item.id}'/>">${item.name}</a>
</c:forEach>
<%-- <a href="/items/old">Old list</a> --%>
<form:form modelAttribute="item" action="${pageContext.request.contextPath}/items">
  <form:input path="name"/>
</form:form>
<jsp:include page="/WEB-INF/jsp/common/footer.jsp"/>
</body>
</html>
